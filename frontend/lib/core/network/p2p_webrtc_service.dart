import 'dart:async';
import 'dart:typed_data';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:uuid/uuid.dart';
import '../utils/debug_log_service.dart';

/// ───────────────────────────────────────────────────────────────
///  P2PWebRTCService — Cross-Internet Chunk Transfer Engine
///
///  Uses WebRTC DataChannels + Firestore Signaling for:
///   • STUN-based NAT hole-punching (works on ~85% of home routers)
///   • End-to-end encrypted chunk bytes sent directly peer-to-peer
///   • No relay server cost for most residential network types
///
///  Signaling Schema (Firestore):
///    signaling/{hostId}/sessions/{sessionId}
///      → offer   : SDP Offer string (set by client/initiator)
///      → answer  : SDP Answer string (set by host/responder)
///      → callerCandidates/{id}: ICE candidates from client
///      → calleeCandidates/{id}: ICE candidates from host
///      → type    : 'upload' | 'download'
///      → chunkId : target chunk identifier
///      → status  : 'pending' | 'connected' | 'done' | 'error'
/// ───────────────────────────────────────────────────────────────
class P2PWebRTCService {
  static final P2PWebRTCService _instance = P2PWebRTCService._internal();
  factory P2PWebRTCService() => _instance;
  P2PWebRTCService._internal();

  final FirebaseFirestore _firestore = FirebaseFirestore.instance;
  final Uuid _uuid = const Uuid();

  /// Google & Global Public STUN servers — NAT hole-punching
  static const Map<String, dynamic> _rtcConfig = {
    'iceServers': [
      {'urls': 'stun:stun.l.google.com:19302'},
      {'urls': 'stun:stun1.l.google.com:19302'},
      {'urls': 'stun:stun2.l.google.com:19302'},
      {'urls': 'stun:stun3.l.google.com:19302'},
      {'urls': 'stun:stun4.l.google.com:19302'},
      {'urls': 'stun:global.stun.twilio.com:3478'},
    ],
    'sdpSemantics': 'unified-plan',
  };

  static const Map<String, dynamic> _dataChannelConstraints = {
    'mandatory': {},
    'optional': [
      {'DtlsSrtpKeyAgreement': true},
    ],
  };

  // Active host-side session listeners (keyed by hostId)
  final Map<String, StreamSubscription> _hostListeners = {};

  // Callback for writing received chunks (set by host side)
  Future<void> Function(String chunkId, Uint8List bytes)? onChunkReceived;

  // ─────────────────────────────────────────────────────────────
  // CLIENT SIDE — Upload chunk to remote host via WebRTC
  // ─────────────────────────────────────────────────────────────

  /// Sends [chunkBytes] to the given [hostId] via WebRTC DataChannel.
  /// Returns true if transfer succeeded, false if it failed.
  Future<bool> uploadChunkViaPeer({
    required String hostId,
    required String chunkId,
    required Uint8List chunkBytes,
    Duration timeout = const Duration(seconds: 45),
  }) async {
    final sessionId = _uuid.v4();
    final sessionRef = _firestore
        .collection('signaling')
        .doc(hostId)
        .collection('sessions')
        .doc(sessionId);

    RTCPeerConnection? pc;
    RTCDataChannel? dc;
    final completer = Completer<bool>();

    try {
      DebugLogService().info('[P2PRTC] Initiating upload session $sessionId → host $hostId (chunk: $chunkId, ${chunkBytes.length} bytes)');

      pc = await createPeerConnection(_rtcConfig, _dataChannelConstraints);

      // Create DataChannel (ordered, reliable)
      final init = RTCDataChannelInit()
        ..ordered = true
        ..maxRetransmits = 5;
      dc = await pc.createDataChannel('chunk-$chunkId', init);

      // Track ICE state
      pc.onIceConnectionState = (state) {
        DebugLogService().info('[P2PRTC] ICE state → $state');
        if (state == RTCIceConnectionState.RTCIceConnectionStateFailed ||
            state == RTCIceConnectionState.RTCIceConnectionStateDisconnected) {
          if (!completer.isCompleted) completer.complete(false);
        }
      };

      // Gather ICE candidates and write to Firestore
      pc.onIceCandidate = (candidate) async {
        try {
          await sessionRef.collection('callerCandidates').add(candidate.toMap());
        } catch (_) {}
      };

      // DataChannel events
      dc.onDataChannelState = (state) async {
        DebugLogService().info('[P2PRTC] DataChannel state → $state');
        if (state == RTCDataChannelState.RTCDataChannelOpen) {
          // Channel open — send chunk size header then chunk bytes
          try {
            // 1. Send handshake: chunkId|size
            final handshake = '$chunkId|${chunkBytes.length}';
            dc!.send(RTCDataChannelMessage(handshake));

            // 2. Send chunk bytes in 64KB slices (WebRTC message size limit)
            const int kSliceSize = 65536;
            int offset = 0;
            while (offset < chunkBytes.length) {
              final end = (offset + kSliceSize).clamp(0, chunkBytes.length);
              final slice = chunkBytes.sublist(offset, end);
              dc.send(RTCDataChannelMessage.fromBinary(slice));
              offset = end;
              // Yield to event loop periodically to avoid blocking
              if (offset % (kSliceSize * 4) == 0) {
                await Future.delayed(Duration.zero);
              }
            }

            // 3. Send EOF sentinel
            dc.send(RTCDataChannelMessage('__EOF__'));
            DebugLogService().info('[P2PRTC] Upload complete — sent ${chunkBytes.length} bytes for $chunkId');
          } catch (e) {
            DebugLogService().warn('[P2PRTC] DataChannel send error: $e');
            if (!completer.isCompleted) completer.complete(false);
          }
        } else if (state == RTCDataChannelState.RTCDataChannelClosed) {
          if (!completer.isCompleted) completer.complete(false);
        }
      };

      dc.onMessage = (msg) {
        // Host sends back 'ACK' on successful write
        if (!completer.isCompleted && msg.text == 'ACK:$chunkId') {
          DebugLogService().info('[P2PRTC] Received ACK from host for chunk $chunkId');
          completer.complete(true);
        } else if (!completer.isCompleted && msg.text.startsWith('ERR:')) {
          completer.complete(false);
        }
      };

      // Create SDP offer
      final offer = await pc.createOffer({});
      await pc.setLocalDescription(offer);

      // Write offer + session metadata to Firestore
      await sessionRef.set({
        'offer': offer.sdp,
        'type': 'upload',
        'chunkId': chunkId,
        'chunkSize': chunkBytes.length,
        'status': 'pending',
        'createdAt': FieldValue.serverTimestamp(),
      });

      // Wait for answer from host (via Firestore listener)
      final answerSub = sessionRef.snapshots().listen((snap) async {
        if (!snap.exists) return;
        final d = snap.data()!;
        if (d['answer'] != null && pc!.signalingState == RTCSignalingState.RTCSignalingStateHaveLocalOffer) {
          try {
            await pc.setRemoteDescription(RTCSessionDescription(d['answer'], 'answer'));
            DebugLogService().info('[P2PRTC] Set remote answer for session $sessionId');
          } catch (e) {
            DebugLogService().warn('[P2PRTC] setRemoteDescription error: $e');
          }
        }
      });

      // Listen for host ICE candidates
      final calleeCandsSub = sessionRef.collection('calleeCandidates').snapshots().listen((snap) async {
        for (final change in snap.docChanges) {
          if (change.type == DocumentChangeType.added) {
            try {
              final cand = change.doc.data()!;
              await pc!.addCandidate(RTCIceCandidate(
                cand['candidate'],
                cand['sdpMid'],
                cand['sdpMLineIndex'],
              ));
            } catch (_) {}
          }
        }
      });

      // Wait for result with timeout
      final success = await completer.future.timeout(timeout, onTimeout: () => false);

      await answerSub.cancel();
      await calleeCandsSub.cancel();

      return success;
    } catch (e) {
      DebugLogService().warn('[P2PRTC] uploadChunkViaPeer error: $e');
      return false;
    } finally {
      try {
        await dc?.close();
        await pc?.close();
        // Cleanup signaling document after session
        await sessionRef.delete();
      } catch (_) {}
    }
  }

  // ─────────────────────────────────────────────────────────────
  // CLIENT SIDE — Download chunk from remote host via WebRTC
  // ─────────────────────────────────────────────────────────────

  /// Downloads a chunk from [hostId] via WebRTC DataChannel.
  /// Returns the raw chunk bytes, or null on failure.
  Future<Uint8List?> downloadChunkViaPeer({
    required String hostId,
    required String chunkId,
    Duration timeout = const Duration(seconds: 60),
  }) async {
    final sessionId = _uuid.v4();
    final sessionRef = _firestore
        .collection('signaling')
        .doc(hostId)
        .collection('sessions')
        .doc(sessionId);

    RTCPeerConnection? pc;
    RTCDataChannel? dc;
    final completer = Completer<Uint8List?>();

    try {
      DebugLogService().info('[P2PRTC] Initiating download session $sessionId → host $hostId (chunk: $chunkId)');

      pc = await createPeerConnection(_rtcConfig, _dataChannelConstraints);

      final init = RTCDataChannelInit()
        ..ordered = true
        ..maxRetransmits = 5;
      dc = await pc.createDataChannel('chunk-$chunkId', init);

      int? expectedSize;
      final List<int> buffer = [];
      bool receivingBinary = false;

      pc.onIceConnectionState = (state) {
        if (state == RTCIceConnectionState.RTCIceConnectionStateFailed ||
            state == RTCIceConnectionState.RTCIceConnectionStateDisconnected) {
          if (!completer.isCompleted) completer.complete(null);
        }
      };

      pc.onIceCandidate = (candidate) async {
        try {
          await sessionRef.collection('callerCandidates').add(candidate.toMap());
        } catch (_) {}
      };

      dc.onDataChannelState = (state) {
        DebugLogService().info('[P2PRTC] Download DataChannel state → $state');
        if (state == RTCDataChannelState.RTCDataChannelClosed) {
          if (!completer.isCompleted) completer.complete(null);
        }
      };

      dc.onMessage = (msg) {
        if (!receivingBinary) {
          // First message is the header: "chunkId|size"
          final parts = msg.text.split('|');
          if (parts.length == 2) {
            expectedSize = int.tryParse(parts[1]);
            receivingBinary = true;
            DebugLogService().info('[P2PRTC] Receiving chunk ${parts[0]} (${parts[1]} bytes)');
          } else if (msg.text == '__EOF__') {
            if (!completer.isCompleted) {
              completer.complete(buffer.isNotEmpty ? Uint8List.fromList(buffer) : null);
            }
          }
        } else {
          if (msg.isBinary) {
            buffer.addAll(msg.binary);
            // Check if we've received all expected bytes
            if (expectedSize != null && buffer.length >= expectedSize!) {
              if (!completer.isCompleted) {
                DebugLogService().info('[P2PRTC] Chunk fully received: ${buffer.length} bytes');
                completer.complete(Uint8List.fromList(buffer));
              }
            }
          } else if (msg.text == '__EOF__') {
            if (!completer.isCompleted) {
              completer.complete(buffer.isNotEmpty ? Uint8List.fromList(buffer) : null);
            }
          }
        }
      };

      // Create offer and write download request to Firestore
      final offer = await pc.createOffer({});
      await pc.setLocalDescription(offer);

      await sessionRef.set({
        'offer': offer.sdp,
        'type': 'download',
        'chunkId': chunkId,
        'status': 'pending',
        'createdAt': FieldValue.serverTimestamp(),
      });

      // Listen for host answer
      final answerSub = sessionRef.snapshots().listen((snap) async {
        if (!snap.exists) return;
        final d = snap.data()!;
        if (d['answer'] != null && pc!.signalingState == RTCSignalingState.RTCSignalingStateHaveLocalOffer) {
          try {
            await pc.setRemoteDescription(RTCSessionDescription(d['answer'], 'answer'));
          } catch (_) {}
        }
      });

      // Listen for host ICE candidates
      final calleeCandsSub = sessionRef.collection('calleeCandidates').snapshots().listen((snap) async {
        for (final change in snap.docChanges) {
          if (change.type == DocumentChangeType.added) {
            try {
              final cand = change.doc.data()!;
              await pc!.addCandidate(RTCIceCandidate(
                cand['candidate'],
                cand['sdpMid'],
                cand['sdpMLineIndex'],
              ));
            } catch (_) {}
          }
        }
      });

      final result = await completer.future.timeout(timeout, onTimeout: () => null);

      await answerSub.cancel();
      await calleeCandsSub.cancel();

      return result;
    } catch (e) {
      DebugLogService().warn('[P2PRTC] downloadChunkViaPeer error: $e');
      return null;
    } finally {
      try {
        await dc?.close();
        await pc?.close();
        await sessionRef.delete();
      } catch (_) {}
    }
  }

  // ─────────────────────────────────────────────────────────────
  // HOST SIDE — Listen for incoming P2P sessions
  // ─────────────────────────────────────────────────────────────

  /// Starts listening for incoming WebRTC session requests for [hostId].
  /// [onChunkReadRequest] is called to read a chunk from local storage (for download).
  /// [onChunkWriteRequest] is called to write received bytes to local storage (for upload).
  void startHostListener({
    required String hostId,
    required Future<Uint8List?> Function(String chunkId) onChunkReadRequest,
    required Future<void> Function(String chunkId, Uint8List bytes) onChunkWriteRequest,
  }) {
    if (_hostListeners.containsKey(hostId)) {
      DebugLogService().info('[P2PRTC] Host listener already active for: $hostId');
      return;
    }

    DebugLogService().info('[P2PRTC] Host listener started for hostId: $hostId');

    final sessionsRef = _firestore
        .collection('signaling')
        .doc(hostId)
        .collection('sessions');

    final sub = sessionsRef
        .where('status', isEqualTo: 'pending')
        .snapshots()
        .listen((snap) {
      for (final change in snap.docChanges) {
        if (change.type == DocumentChangeType.added) {
          final sessionData = change.doc.data();
          if (sessionData == null) continue;
          final sessionId = change.doc.id;
          final sessionRef = sessionsRef.doc(sessionId);
          final type = sessionData['type']?.toString() ?? '';
          final chunkId = sessionData['chunkId']?.toString() ?? '';

          if (type == 'upload') {
            _handleIncomingUploadSession(
              sessionRef: sessionRef,
              sessionId: sessionId,
              sessionData: sessionData,
              chunkId: chunkId,
              onWrite: onChunkWriteRequest,
            );
          } else if (type == 'download') {
            _handleIncomingDownloadSession(
              sessionRef: sessionRef,
              sessionId: sessionId,
              sessionData: sessionData,
              chunkId: chunkId,
              onRead: onChunkReadRequest,
            );
          }
        }
      }
    }, onError: (e) {
      DebugLogService().warn('[P2PRTC] Host listener stream error: $e');
    });

    _hostListeners[hostId] = sub;
  }

  /// Handles an incoming upload session (client is pushing a chunk to this host).
  Future<void> _handleIncomingUploadSession({
    required DocumentReference sessionRef,
    required String sessionId,
    required Map<String, dynamic> sessionData,
    required String chunkId,
    required Future<void> Function(String chunkId, Uint8List bytes) onWrite,
  }) async {
    RTCPeerConnection? pc;

    try {
      DebugLogService().info('[P2PRTC] [HOST] Handling incoming UPLOAD session $sessionId for chunk $chunkId');

      pc = await createPeerConnection(_rtcConfig, _dataChannelConstraints);

      int? expectedSize;
      final List<int> buffer = [];
      bool receivingBinary = false;

      // Accept DataChannel from client
      pc.onDataChannel = (channel) {
        // channel is used directly in closures below
        DebugLogService().info('[P2PRTC] [HOST] DataChannel received: ${channel.label}');

        channel.onMessage = (msg) async {
          if (!receivingBinary) {
            final parts = msg.text.split('|');
            if (parts.length == 2) {
              expectedSize = int.tryParse(parts[1]);
              receivingBinary = true;
            } else if (msg.text == '__EOF__') {
              await _finalizeUploadWrite(
                chunkId: chunkId,
                buffer: buffer,
                channel: channel,
                sessionRef: sessionRef,
                onWrite: onWrite,
              );
            }
          } else {
            if (msg.isBinary) {
              buffer.addAll(msg.binary);
              if (expectedSize != null && buffer.length >= expectedSize!) {
                await _finalizeUploadWrite(
                  chunkId: chunkId,
                  buffer: buffer,
                  channel: channel,
                  sessionRef: sessionRef,
                  onWrite: onWrite,
                );
              }
            } else if (msg.text == '__EOF__') {
              await _finalizeUploadWrite(
                chunkId: chunkId,
                buffer: buffer,
                channel: channel,
                sessionRef: sessionRef,
                onWrite: onWrite,
              );
            }
          }
        };
      };

      // Gather ICE candidates
      pc.onIceCandidate = (candidate) async {
        try {
          await sessionRef.collection('calleeCandidates').add(candidate.toMap());
        } catch (_) {}
      };

      // Set remote offer + create answer
      final offer = sessionData['offer']?.toString() ?? '';
      await pc.setRemoteDescription(RTCSessionDescription(offer, 'offer'));
      final answer = await pc.createAnswer({});
      await pc.setLocalDescription(answer);

      // Write answer to Firestore for client to pick up
      await sessionRef.update({'answer': answer.sdp, 'status': 'connected'});

      // Add client ICE candidates
      sessionRef.collection('callerCandidates').snapshots().listen((snap) async {
        for (final change in snap.docChanges) {
          if (change.type == DocumentChangeType.added) {
            try {
              final cand = change.doc.data()!;
              await pc!.addCandidate(RTCIceCandidate(
                cand['candidate'],
                cand['sdpMid'],
                cand['sdpMLineIndex'],
              ));
            } catch (_) {}
          }
        }
      });
    } catch (e) {
      DebugLogService().warn('[P2PRTC] [HOST] Upload session $sessionId error: $e');
      try { await sessionRef.update({'status': 'error'}); } catch (_) {}
    }
  }

  Future<void> _finalizeUploadWrite({
    required String chunkId,
    required List<int> buffer,
    required RTCDataChannel channel,
    required DocumentReference sessionRef,
    required Future<void> Function(String chunkId, Uint8List bytes) onWrite,
  }) async {
    try {
      if (buffer.isNotEmpty) {
        await onWrite(chunkId, Uint8List.fromList(buffer));
        channel.send(RTCDataChannelMessage('ACK:$chunkId'));
        await sessionRef.update({'status': 'done'});
        DebugLogService().info('[P2PRTC] [HOST] Stored ${buffer.length} bytes for chunk $chunkId, sent ACK');
      } else {
        channel.send(RTCDataChannelMessage('ERR:empty'));
        await sessionRef.update({'status': 'error'});
      }
    } catch (e) {
      DebugLogService().warn('[P2PRTC] [HOST] Finalize upload write error: $e');
      try { channel.send(RTCDataChannelMessage('ERR:$e')); } catch (_) {}
    }
  }

  /// Handles an incoming download session (client is pulling a chunk from this host).
  Future<void> _handleIncomingDownloadSession({
    required DocumentReference sessionRef,
    required String sessionId,
    required Map<String, dynamic> sessionData,
    required String chunkId,
    required Future<Uint8List?> Function(String chunkId) onRead,
  }) async {
    RTCPeerConnection? pc;

    try {
      DebugLogService().info('[P2PRTC] [HOST] Handling incoming DOWNLOAD session $sessionId for chunk $chunkId');

      pc = await createPeerConnection(_rtcConfig, _dataChannelConstraints);

      // Handle DataChannel created by the client
      pc.onDataChannel = (channel) {
        DebugLogService().info('[P2PRTC] [HOST] Download DataChannel received from client: ${channel.label}');

        Future<void> sendChunkData() async {
          try {
            final bytes = await onRead(chunkId);
            if (bytes != null && bytes.isNotEmpty) {
              channel.send(RTCDataChannelMessage('$chunkId|${bytes.length}'));
              const int kSliceSize = 65536;
              int offset = 0;
              while (offset < bytes.length) {
                final end = (offset + kSliceSize).clamp(0, bytes.length);
                channel.send(RTCDataChannelMessage.fromBinary(bytes.sublist(offset, end)));
                offset = end;
                if (offset % (kSliceSize * 4) == 0) {
                  await Future.delayed(Duration.zero);
                }
              }
              channel.send(RTCDataChannelMessage('__EOF__'));
              await sessionRef.update({'status': 'done'});
              DebugLogService().info('[P2PRTC] [HOST] Sent ${bytes.length} bytes for chunk $chunkId to peer');
            } else {
              channel.send(RTCDataChannelMessage('ERR:not_found'));
              await sessionRef.update({'status': 'error'});
            }
          } catch (e) {
            DebugLogService().warn('[P2PRTC] [HOST] Download send error: $e');
            try { channel.send(RTCDataChannelMessage('ERR:$e')); } catch (_) {}
          }
        }

        if (channel.state == RTCDataChannelState.RTCDataChannelOpen) {
          sendChunkData();
        } else {
          channel.onDataChannelState = (state) {
            if (state == RTCDataChannelState.RTCDataChannelOpen) {
              sendChunkData();
            }
          };
        }
      };

      pc.onIceCandidate = (candidate) async {
        try {
          await sessionRef.collection('calleeCandidates').add(candidate.toMap());
        } catch (_) {}
      };

      // Set remote offer (from client) and create answer
      final offer = sessionData['offer']?.toString() ?? '';
      await pc.setRemoteDescription(RTCSessionDescription(offer, 'offer'));
      final answer = await pc.createAnswer({});
      await pc.setLocalDescription(answer);
      await sessionRef.update({'answer': answer.sdp, 'status': 'connected'});

      // Add client ICE candidates
      sessionRef.collection('callerCandidates').snapshots().listen((snap) async {
        for (final change in snap.docChanges) {
          if (change.type == DocumentChangeType.added) {
            try {
              final cand = change.doc.data()!;
              await pc!.addCandidate(RTCIceCandidate(
                cand['candidate'],
                cand['sdpMid'],
                cand['sdpMLineIndex'],
              ));
            } catch (_) {}
          }
        }
      });
    } catch (e) {
      DebugLogService().warn('[P2PRTC] [HOST] Download session $sessionId error: $e');
      try { await sessionRef.update({'status': 'error'}); } catch (_) {}
    }
  }

  /// Stops the WebRTC host listener for [hostId].
  Future<void> stopHostListener(String hostId) async {
    final sub = _hostListeners.remove(hostId);
    await sub?.cancel();
    DebugLogService().info('[P2PRTC] Host listener stopped for: $hostId');
  }

  /// Cleans up stale signaling sessions (older than 5 minutes) for [hostId].
  Future<void> cleanupStaleSessions(String hostId) async {
    try {
      final cutoff = DateTime.now().subtract(const Duration(minutes: 5));
      final stale = await _firestore
          .collection('signaling')
          .doc(hostId)
          .collection('sessions')
          .where('createdAt', isLessThan: Timestamp.fromDate(cutoff))
          .get();
      for (final doc in stale.docs) {
        await doc.reference.delete();
      }
    } catch (_) {}
  }
}
