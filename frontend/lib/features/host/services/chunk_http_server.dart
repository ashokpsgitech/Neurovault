import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import '../../../core/network/p2p_webrtc_service.dart';
import '../../../core/utils/debug_log_service.dart';
import '../data/host_repository.dart';

/// Lightweight built-in Dart HTTP server running on the host device.
/// Serves encrypted chunk payloads directly from local storage.container binary file
/// to authenticated network clients via GET /api/storage/chunks/{chunkId}.
/// Also accepts POST /api/storage/chunks/{chunkId} to write incoming chunk payloads.
class ChunkHttpServer {
  static final ChunkHttpServer _instance = ChunkHttpServer._internal();
  factory ChunkHttpServer() => _instance;
  ChunkHttpServer._internal();

  static const int kPort = 8080;

  HttpServer? _server;
  String? _containerPath;
  String? _hostId;
  bool _running = false;

  bool get isRunning => _running;

  /// Starts the chunk HTTP server bound to all network interfaces on port 8080,
  /// and simultaneously starts the WebRTC P2P host listener for cross-internet transfers.
  Future<void> start(String containerPath, {String? hostId}) async {
    if (_running) {
      _containerPath = containerPath;
      return;
    }
    _containerPath = containerPath;
    _hostId = hostId;
    try {
      _server = await HttpServer.bind(InternetAddress.anyIPv4, kPort, shared: true);
      _running = true;
      DebugLogService().info('[ChunkHttpServer] Host chunk server started on port $kPort — serving from: $containerPath');
      _handleRequests();
    } catch (e) {
      DebugLogService().warn('[ChunkHttpServer] Failed to start chunk server on port $kPort: $e');
    }

    // Start WebRTC P2P host listener for cross-internet chunk transfers
    if (hostId != null && hostId.isNotEmpty) {
      try {
        P2PWebRTCService().startHostListener(
          hostId: hostId,
          onChunkReadRequest: _readChunkForP2P,
          onChunkWriteRequest: _writeChunkForP2P,
        );
        DebugLogService().info('[ChunkHttpServer] WebRTC P2P listener active for hostId: $hostId');
      } catch (e) {
        DebugLogService().warn('[ChunkHttpServer] WebRTC P2P listener startup error: $e');
      }
    }
  }

  /// Stops the chunk HTTP server and WebRTC P2P listener.
  Future<void> stop() async {
    _running = false;
    await _server?.close(force: true);
    _server = null;
    DebugLogService().info('[ChunkHttpServer] Host chunk server stopped.');

    // Stop WebRTC host listener
    if (_hostId != null && _hostId!.isNotEmpty) {
      try {
        await P2PWebRTCService().stopHostListener(_hostId!);
      } catch (_) {}
    }
  }

  void updateContainerPath(String path) {
    _containerPath = path;
  }

  /// Activates the WebRTC P2P listener with the given [hostId].
  /// Call this after host registration to enable cross-internet chunk transfers.
  void activateWebRTCListener(String hostId) {
    _hostId = hostId;
    try {
      P2PWebRTCService().startHostListener(
        hostId: hostId,
        onChunkReadRequest: _readChunkForP2P,
        onChunkWriteRequest: _writeChunkForP2P,
      );
      DebugLogService().info('[ChunkHttpServer] WebRTC P2P listener activated for hostId: $hostId');
    } catch (e) {
      DebugLogService().warn('[ChunkHttpServer] WebRTC P2P activateWebRTCListener error: $e');
    }
  }

  // ─── WebRTC P2P chunk read/write callbacks ───

  Future<Uint8List?> _readChunkForP2P(String chunkId) async {
    final path = _containerPath;
    if (path == null || path.isEmpty) return null;
    try {
      // Parse chunk index from chunkId format: {fileId}_chunk_{index}
      int chunkIndex = 0;
      final match = RegExp(r'_chunk_(\d+)$').firstMatch(chunkId);
      if (match != null) chunkIndex = int.tryParse(match.group(1) ?? '0') ?? 0;
      final bytes = await HostRepository().readChunkFromLocalContainer(path, chunkIndex, 0, chunkId: chunkId);
      DebugLogService().info('[ChunkHttpServer][P2P] Read chunk $chunkId (${bytes?.length ?? 0} bytes) for WebRTC peer');
      return bytes;
    } catch (e) {
      DebugLogService().warn('[ChunkHttpServer][P2P] Error reading chunk $chunkId for P2P: $e');
      return null;
    }
  }

  Future<void> _writeChunkForP2P(String chunkId, Uint8List bytes) async {
    final path = _containerPath;
    if (path == null || path.isEmpty) {
      throw Exception('[ChunkHttpServer][P2P] Container path not set — cannot write chunk');
    }
    try {
      await HostRepository().writeChunkToLocalContainer(path, bytes, chunkId: chunkId);
      DebugLogService().info('[ChunkHttpServer][P2P] Wrote chunk $chunkId (${bytes.length} bytes) from WebRTC peer');
    } catch (e) {
      DebugLogService().warn('[ChunkHttpServer][P2P] Error writing chunk $chunkId from P2P: $e');
      rethrow;
    }
  }

  void _handleRequests() {
    _server?.listen((HttpRequest request) async {
      try {
        final uri = request.uri;
        final pathSegments = uri.pathSegments;

        // Expected: /api/storage/chunks/{chunkId}
        if (pathSegments.length >= 4 &&
            pathSegments[0] == 'api' &&
            pathSegments[1] == 'storage' &&
            pathSegments[2] == 'chunks') {
          final chunkId = pathSegments[3];

          if (request.method == 'GET') {
            await _handleGetChunk(request, chunkId);
          } else if (request.method == 'POST') {
            await _handlePostChunk(request, chunkId);
          } else {
            request.response.statusCode = HttpStatus.methodNotAllowed;
            await request.response.close();
          }
        } else if (uri.path == '/health' || uri.path == '/') {
          request.response
            ..statusCode = HttpStatus.ok
            ..headers.contentType = ContentType.text
            ..write('NeuroVault Host Node OK');
          await request.response.close();
        } else {
          request.response.statusCode = HttpStatus.notFound;
          await request.response.close();
        }
      } catch (e) {
        DebugLogService().warn('[ChunkHttpServer] Request handler error: $e');
        try {
          request.response.statusCode = HttpStatus.internalServerError;
          await request.response.close();
        } catch (_) {}
      }
    }, onError: (e) {
      DebugLogService().warn('[ChunkHttpServer] Server stream error: $e');
    });
  }

  Future<void> _handleGetChunk(HttpRequest request, String chunkId) async {
    final path = _containerPath;
    if (path == null || path.isEmpty) {
      request.response.statusCode = HttpStatus.serviceUnavailable;
      await request.response.close();
      return;
    }

    // Parse chunk index from chunkId: format = {fileId}_chunk_{index}
    int chunkIndex = 0;
    final match = RegExp(r'_chunk_(\d+)$').firstMatch(chunkId);
    if (match != null) {
      chunkIndex = int.tryParse(match.group(1) ?? '0') ?? 0;
    }

    try {
      final bytes = await HostRepository().readChunkFromLocalContainer(path, chunkIndex, 0, chunkId: chunkId);
      if (bytes != null && bytes.isNotEmpty) {
        request.response
          ..statusCode = HttpStatus.ok
          ..headers.contentType = ContentType.binary
          ..headers.set('Content-Length', bytes.length)
          ..headers.set('X-Chunk-Id', chunkId)
          ..headers.set('X-Chunk-Size', bytes.length);
        request.response.add(bytes);
        await request.response.flush();
        await request.response.close();
        DebugLogService().info('[ChunkHttpServer] Served chunk: $chunkId (${bytes.length} bytes)');
      } else {
        request.response.statusCode = HttpStatus.notFound;
        await request.response.close();
        DebugLogService().warn('[ChunkHttpServer] Chunk not found in container: $chunkId');
      }
    } catch (e) {
      DebugLogService().warn('[ChunkHttpServer] Error reading chunk $chunkId: $e');
      request.response.statusCode = HttpStatus.internalServerError;
      await request.response.close();
    }
  }

  Future<void> _handlePostChunk(HttpRequest request, String chunkId) async {
    final path = _containerPath;
    if (path == null || path.isEmpty) {
      request.response.statusCode = HttpStatus.serviceUnavailable;
      await request.response.close();
      return;
    }

    try {
      final bodyBytes = await request.fold<List<int>>([], (buf, chunk) => buf..addAll(chunk));
      if (bodyBytes.isEmpty) {
        request.response.statusCode = HttpStatus.badRequest;
        await request.response.close();
        return;
      }

      await HostRepository().writeChunkToLocalContainer(path, Uint8List.fromList(bodyBytes), chunkId: chunkId);
      request.response
        ..statusCode = HttpStatus.ok
        ..headers.contentType = ContentType.json
        ..write('{"status":"stored","chunkId":"$chunkId","bytes":${bodyBytes.length}}');
      await request.response.close();
      DebugLogService().info('[ChunkHttpServer] Received and stored chunk: $chunkId (${bodyBytes.length} bytes)');
    } catch (e) {
      DebugLogService().warn('[ChunkHttpServer] Error storing chunk $chunkId: $e');
      request.response.statusCode = HttpStatus.internalServerError;
      await request.response.close();
    }
  }
}
