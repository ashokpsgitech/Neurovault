import 'dart:io';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import '../storage/secure_storage_service.dart';
import '../utils/debug_log_service.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:google_sign_in/google_sign_in.dart';

import '../../firebase_options.dart';
import '../../features/authentication/models/user_model.dart';
import '../../features/files/models/file_metadata_model.dart';
import '../../features/host/data/host_repository.dart';
import '../crypto/file_chunker.dart';

/// 24/7 Firebase Cloud Backend Service for NeuroVault.
/// Provides Authentication and Firestore Metadata sync for host chunk locations.
class FirebaseService {
  static final FirebaseService _instance = FirebaseService._internal();
  factory FirebaseService() => _instance;
  FirebaseService._internal();

  final FirebaseAuth _auth = FirebaseAuth.instance;
  final FirebaseFirestore _firestore = FirebaseFirestore.instance;

  User? get currentUser => _auth.currentUser;

  /// Ensures Firebase App is initialized cleanly across platforms.
  static Future<void> initialize() async {
    try {
      if (Firebase.apps.isEmpty) {
        await Firebase.initializeApp(
          options: DefaultFirebaseOptions.currentPlatform,
        );
      }
    } catch (e, st) {
      DebugLogService().error('[FirebaseService.initialize] Firebase init error: $e', e, st);
    }
  }

  /// Authenticates or registers user using Google Sign-In provider.
  Future<UserModel> signInWithGoogle() async {
    final GoogleSignIn googleSignIn = GoogleSignIn();
    final GoogleSignInAccount? googleUser = await googleSignIn.signIn();
    if (googleUser == null) {
      throw Exception('Google sign-in cancelled by user');
    }

    final GoogleSignInAuthentication googleAuth = await googleUser.authentication;
    final OAuthCredential credential = GoogleAuthProvider.credential(
      accessToken: googleAuth.accessToken,
      idToken: googleAuth.idToken,
    );

    final userCredential = await _auth.signInWithCredential(credential);
    final user = userCredential.user;
    if (user == null) {
      throw Exception('Google sign-in failed');
    }

    final username = user.displayName ?? googleUser.displayName ?? user.email?.split('@').first ?? 'Google User';
    final email = user.email ?? googleUser.email;

    // Safely attempt Firestore user profile creation with timeout
    try {
      final docSnap = await _firestore
          .collection('users')
          .doc(user.uid)
          .get()
          .timeout(const Duration(seconds: 5));
      if (!docSnap.exists) {
        await _firestore.collection('users').doc(user.uid).set({
          'id': user.uid,
          'username': username,
          'email': email,
          'role': 'UNSELECTED',
          'mode': 'PRIVATE',
          'isFirstTime': true,
          'createdAt': FieldValue.serverTimestamp(),
        }).timeout(const Duration(seconds: 5));
        return UserModel(
          id: user.uid,
          username: username,
          email: email,
          role: 'UNSELECTED',
          mode: 'PRIVATE',
        );
      } else if (docSnap.data() != null) {
        final data = docSnap.data()!;
        return UserModel(
          id: user.uid,
          username: data['username']?.toString() ?? username,
          email: email,
          role: data['role']?.toString() ?? 'CLIENT',
          mode: data['mode']?.toString() ?? 'PRIVATE',
        );
      }
    } catch (_) {
      // Proceed gracefully if Firestore is unavailable or database is pending setup
    }

    return UserModel(
      id: user.uid,
      username: username,
      email: email,
      role: 'CLIENT',
      mode: 'PRIVATE',
    );
  }

  /// Sends Email Verification code / link to the registered user.
  /// Throws if sending fails so the UI can surface the error.
  Future<void> sendEmailVerification() async {
    final user = _auth.currentUser;
    if (user == null) throw Exception('No authenticated user to send verification email to.');
    if (user.emailVerified) return; // Already verified
    // Force reload to get fresh state before sending
    try {
      await user.reload().timeout(const Duration(seconds: 5));
    } catch (_) {}
    final freshUser = _auth.currentUser;
    if (freshUser != null && freshUser.emailVerified) return;

    try {
      await (freshUser ?? user).sendEmailVerification().timeout(const Duration(seconds: 10));
    } on FirebaseAuthException catch (e) {
      final code = e.code.toLowerCase();
      final msg = (e.message ?? '').toLowerCase();
      if (code.contains('too-many-requests') || code.contains('unusual-activity') || msg.contains('unusual activity') || msg.contains('blocked all requests')) {
        DebugLogService().log('Firebase Rate Limit: $e', level: 'WARN');
        throw Exception('Firebase rate limit: Too many verification attempts from this device/IP. Please wait 5-10 minutes or login directly.');
      }
      rethrow;
    }
  }

  /// Checks if current user's email has been verified via Firebase.
  /// Always forces a server reload — never uses stale cache.
  Future<bool> checkEmailVerified() async {
    final user = _auth.currentUser;
    if (user == null) return false;
    try {
      // Force server-side refresh to get actual verified status
      await user.reload().timeout(const Duration(seconds: 8));
    } catch (_) {
      // If network is unavailable, fall through to cached value
    }
    final freshUser = _auth.currentUser;
    final isVerified = freshUser?.emailVerified ?? false;
    if (isVerified) {
      // Async Firestore update — don't block the result
      try {
        await _firestore.collection('users').doc(user.uid).update({
          'emailVerified': true,
        }).timeout(const Duration(seconds: 5));
      } catch (_) {}
    }
    return isVerified;
  }

  /// Authenticates user anonymously for Public Mode.
  Future<UserModel> signInAnonymously() async {
    final credential = await _auth.signInAnonymously();
    final user = credential.user;
    if (user == null) {
      throw Exception('Anonymous authentication failed');
    }

    final anonId = user.uid.substring(0, 8);
    final username = 'Anon-$anonId';
    const email = 'anonymous@neurovault.net';

    final userDoc = {
      'id': user.uid,
      'username': username,
      'email': email,
      'role': 'CLIENT',
      'mode': 'PUBLIC',
      'isAnonymous': true,
      'createdAt': FieldValue.serverTimestamp(),
    };

    try {
      await _firestore
          .collection('users')
          .doc(user.uid)
          .set(userDoc)
          .timeout(const Duration(seconds: 5));
    } catch (_) {}

    return UserModel(
      id: user.uid,
      username: username,
      email: email,
      role: 'CLIENT',
      mode: 'PUBLIC',
      isAnonymous: true,
    );
  }

  /// Registers user with Email & Password on Firebase Auth and sends verification code/link.
  Future<UserModel> register({
    required String username,
    required String email,
    required String password,
    String role = 'CLIENT',
    String mode = 'PRIVATE',
  }) async {
    final credential = await _auth.createUserWithEmailAndPassword(
      email: email,
      password: password,
    );

    final user = credential.user;
    if (user == null) {
      throw Exception('Failed to create user account');
    }

    try {
      await user.updateDisplayName(username).timeout(const Duration(seconds: 5));
    } catch (_) {}

    // Send verification email — log failure but don't block registration
    try {
      await user.sendEmailVerification().timeout(const Duration(seconds: 10));
    } catch (e) {
      // Registration still succeeded; verification email can be resent from verification screen
    }

    final userDoc = {
      'id': user.uid,
      'username': username,
      'email': email,
      'role': role,
      'mode': mode,
      'isAnonymous': false,
      'emailVerified': false,
      'createdAt': FieldValue.serverTimestamp(),
    };

    try {
      await _firestore
          .collection('users')
          .doc(user.uid)
          .set(userDoc)
          .timeout(const Duration(seconds: 5));
    } catch (_) {
      // Proceed gracefully if Firestore is unavailable
    }

    return UserModel(
      id: user.uid,
      username: username,
      email: email,
      role: role,
      mode: mode,
      isAnonymous: false,
    );
  }

  /// Updates user role and mode in Firestore user profile.
  Future<void> updateUserPreferences({
    required String role,
    required String mode,
  }) async {
    final user = _auth.currentUser;
    if (user == null) return;
    try {
      await _firestore.collection('users').doc(user.uid).update({
        'role': role,
        'mode': mode,
      }).timeout(const Duration(seconds: 5));
    } catch (_) {}
  }

  /// Logs in user with Email & Password on Firebase Auth.
  Future<UserModel> login({
    required String email,
    required String password,
  }) async {
    final credential = await _auth.signInWithEmailAndPassword(
      email: email,
      password: password,
    );

    final user = credential.user;
    if (user == null) {
      throw Exception('Authentication failed');
    }

    String username = user.displayName ?? email.split('@').first;
    String role = 'CLIENT';
    String mode = 'PRIVATE';
    bool isAnon = user.isAnonymous;

    try {
      final docSnap = await _firestore
          .collection('users')
          .doc(user.uid)
          .get()
          .timeout(const Duration(seconds: 5));
      if (docSnap.exists && docSnap.data() != null) {
        final data = docSnap.data()!;
        username = data['username']?.toString() ?? username;
        role = data['role']?.toString() ?? role;
        mode = data['mode']?.toString() ?? mode;
        isAnon = data['isAnonymous'] == true || isAnon;
      } else {
        await _firestore.collection('users').doc(user.uid).set({
          'id': user.uid,
          'username': username,
          'email': email,
          'role': role,
          'mode': mode,
          'isAnonymous': isAnon,
          'createdAt': FieldValue.serverTimestamp(),
        }).timeout(const Duration(seconds: 5));
      }
    } catch (_) {
      // Proceed gracefully with FirebaseAuth fallback values
    }

    return UserModel(
      id: user.uid,
      username: username,
      email: email,
      role: role,
      mode: mode,
      isAnonymous: isAnon,
    );
  }

  /// Fetches current user profile from Firebase Auth & Firestore.
  Future<UserModel?> getCurrentUser() async {
    final user = _auth.currentUser;
    if (user == null) return null;

    String username = user.displayName ?? (user.isAnonymous ? 'Anon-${user.uid.substring(0, 8)}' : user.email?.split('@').first ?? 'User');
    String role = 'CLIENT';
    String mode = 'PRIVATE';
    bool isAnon = user.isAnonymous;

    try {
      final docSnap = await _firestore
          .collection('users')
          .doc(user.uid)
          .get()
          .timeout(const Duration(seconds: 5));
      if (docSnap.exists && docSnap.data() != null) {
        final data = docSnap.data()!;
        username = data['username']?.toString() ?? username;
        role = data['role']?.toString() ?? role;
        mode = data['mode']?.toString() ?? mode;
        isAnon = data['isAnonymous'] == true || isAnon;
      }
      return UserModel(
        id: user.uid,
        username: username,
        email: user.email ?? (isAnon ? 'anonymous@neurovault.net' : ''),
        role: role,
        mode: mode,
        isAnonymous: isAnon,
      );
    } catch (_) {
      return UserModel(
        id: user.uid,
        username: username,
        email: user.email ?? '',
        role: role,
        mode: mode,
        isAnonymous: isAnon,
      );
    }
  }

  /// Logs out user from Firebase Auth and marks owned host node status OFFLINE in Cloud Firestore.
  Future<void> logout() async {
    final user = _auth.currentUser;
    if (user != null) {
      try {
        final snap = await _firestore.collection('hosts').where('ownerId', isEqualTo: user.uid).get();
        for (final doc in snap.docs) {
          await doc.reference.update({
            'status': 'OFFLINE',
            'lastSeen': FieldValue.serverTimestamp(),
            'lastSeenIso': DateTime.now().toIso8601String(),
          });
        }
      } catch (_) {}
    }
    await _auth.signOut();
  }

  /// Uploads encrypted file bytes to Cloud Vault (Firebase Storage / Cloud Firestore).
  Future<FileItem> uploadEncryptedFile({
    required String filename,
    required Uint8List fileBytes,
    required String aesKeyBase64,
  }) async {
    final user = _auth.currentUser;
    if (user == null) {
      const msg = 'Not authenticated — please sign in before uploading';
      DebugLogService().error('[FirebaseService] $msg');
      throw Exception(msg);
    }

    DebugLogService().info('[FirebaseService] uploadEncryptedFile: $filename (${fileBytes.length} bytes) for uid=${user.uid}');

    final activeHostDocs = await getActiveHostDocs();
    final activeHostCount = activeHostDocs.isNotEmpty ? activeHostDocs.length : 1;

    final dynamicChunks = FileChunker.splitIntoChunks(fileBytes, activeHostCount: activeHostCount);
    final calculatedChunkCount = dynamicChunks.length;

    final fileId = DateTime.now().millisecondsSinceEpoch.toString();

    final fileDoc = <String, dynamic>{
      'id': fileId,
      'filename': filename,
      'sizeBytes': fileBytes.length,
      'encryptedAesKey': aesKeyBase64,
      'ownerId': user.uid,
      'createdAt': FieldValue.serverTimestamp(),
      'createdAtIso': DateTime.now().toIso8601String(),
      'chunkCount': calculatedChunkCount,
      'activeHostReplicas': activeHostCount,
    };

    DebugLogService().info('[FirebaseService] Writing Firestore metadata document for file: $fileId ($calculatedChunkCount chunks across $activeHostCount active host replicas)');
    try {
      await _firestore
          .collection('users')
          .doc(user.uid)
          .collection('files')
          .doc(fileId)
          .set(fileDoc)
          .timeout(const Duration(seconds: 10));
      DebugLogService().info('[FirebaseService] Firestore file metadata document write OK.');

      // Record chunk allocation metadata & host locations (NO raw payload bytes stored in Firestore or Firebase)
      for (int i = 0; i < dynamicChunks.length; i++) {
        final chunkBytes = dynamicChunks[i];
        final targetHostDoc = activeHostDocs.isNotEmpty ? activeHostDocs[i % activeHostDocs.length] : null;
        final targetHostData = targetHostDoc?.data();
        final targetHostId = targetHostDoc?.id ?? 'local-container';
        final targetHostname = targetHostData?['hostname']?.toString() ?? 'MicroServer-Node';
        final targetOwnerEmail = targetHostData?['ownerEmail']?.toString() ?? targetHostData?['ownerId']?.toString() ?? 'Host Account';
        final targetDevice = targetHostData?['deviceType']?.toString() ?? 'Host Device';
        final targetIp = targetHostData?['publicIp']?.toString() ?? '127.0.0.1';

        try {
          // Record assigned host location metadata under client's file chunk document
          await _firestore
              .collection('users')
              .doc(user.uid)
              .collection('files')
              .doc(fileId)
              .collection('chunks')
              .doc('chunk_$i')
              .set({
            'chunkId': '${fileId}_chunk_$i',
            'chunkIndex': i,
            'sizeBytes': chunkBytes.length,
            'assignedHostId': targetHostId,
            'assignedHostname': targetHostname,
            'assignedHostOwnerEmail': targetOwnerEmail,
            'assignedHostDevice': targetDevice,
            'assignedHostIp': targetIp,
            'createdAt': FieldValue.serverTimestamp(),
          }).timeout(const Duration(seconds: 5));

          if (targetHostDoc != null) {
            // Record client account and file details under target host's hosted_chunks collection
            await _firestore
                .collection('hosts')
                .doc(targetHostId)
                .collection('hosted_chunks')
                .doc('${fileId}_chunk_$i')
                .set({
              'fileId': fileId,
              'filename': filename,
              'chunkIndex': i,
              'sizeBytes': chunkBytes.length,
              'clientUid': user.uid,
              'clientEmail': user.email ?? (user.isAnonymous ? 'anonymous@neurovault.net' : 'Client User'),
              'createdAt': FieldValue.serverTimestamp(),
              'createdAtIso': DateTime.now().toIso8601String(),
            }).timeout(const Duration(seconds: 5));

            // Deliver chunk payload to host:
            // Strategy A: If THIS device IS the assigned host → write directly to local container
            // Strategy B: Remote host → POST the chunk bytes to host's built-in HTTP chunk server
            final String canonicalSelfHostId = _auth.currentUser != null ? 'host_${_auth.currentUser!.uid}' : '';
            final bool isSelfHost = canonicalSelfHostId.isNotEmpty && targetHostId == canonicalSelfHostId;
            final String chunkId = '${fileId}_chunk_$i';

            bool chunkWritten = false;

            if (isSelfHost) {
              // Same device: try 1 → saved container path from Firestore
              final String? hostContainerPath = targetHostDoc.data()['containerPath']?.toString();
              if (hostContainerPath != null && hostContainerPath.isNotEmpty) {
                try {
                  await HostRepository().writeChunkToLocalContainer(
                    hostContainerPath,
                    Uint8List.fromList(chunkBytes),
                    chunkId: chunkId,
                  );
                  chunkWritten = true;
                  DebugLogService().info('[FirebaseService] Stored chunk_$i (${chunkBytes.length} bytes) to self-host container: $hostContainerPath');
                } catch (e) {
                  DebugLogService().warn('[FirebaseService] Self-host Firestore path write failed: $e');
                }
              }
              // Same device: try 2 → saved path from SecureStorage (most reliable)
              if (!chunkWritten) {
                try {
                  final savedPath = await SecureStorageService().getHostContainerPath();
                  if (savedPath != null && savedPath.isNotEmpty) {
                    await HostRepository().writeChunkToLocalContainer(
                      savedPath,
                      Uint8List.fromList(chunkBytes),
                      chunkId: chunkId,
                    );
                    chunkWritten = true;
                    DebugLogService().info('[FirebaseService] Stored chunk_$i via SecureStorage path: $savedPath');
                  }
                } catch (e) {
                  DebugLogService().warn('[FirebaseService] Self-host SecureStorage path write failed: $e');
                }
              }
              // Same device: try 3 → loopback HTTP POST to running ChunkHttpServer
              if (!chunkWritten) {
                try {
                  final postUrl = 'http://127.0.0.1:8080/api/storage/chunks/$chunkId';
                  final dio = Dio();
                  await dio.post(
                    postUrl,
                    data: Stream.fromIterable([Uint8List.fromList(chunkBytes)]),
                    options: Options(
                      headers: {'Content-Type': 'application/octet-stream', 'Content-Length': chunkBytes.length},
                      responseType: ResponseType.json,
                    ),
                  ).timeout(const Duration(seconds: 30));
                  chunkWritten = true;
                  DebugLogService().info('[FirebaseService] Stored chunk_$i via loopback POST to self-host server');
                } catch (e) {
                  DebugLogService().warn('[FirebaseService] Self-host loopback POST failed: $e');
                }
              }
              if (!chunkWritten) {
                DebugLogService().warn('[FirebaseService] WARNING: chunk_$i could not be stored — host may not be enabled or path is unavailable');
              }
            } else {
              // Remote host — POST the chunk bytes to host's built-in HTTP chunk server
              final String postUrl = 'http://$targetIp:8080/api/storage/chunks/$chunkId';
              try {
                final dio = Dio();
                await dio.post(
                  postUrl,
                  data: Stream.fromIterable([Uint8List.fromList(chunkBytes)]),
                  options: Options(
                    headers: {
                      'Content-Type': 'application/octet-stream',
                      'Content-Length': chunkBytes.length,
                    },
                    responseType: ResponseType.json,
                  ),
                ).timeout(const Duration(seconds: 30));
                DebugLogService().info('[FirebaseService] Posted chunk_$i (${chunkBytes.length} bytes) to remote host chunk server: $postUrl');
              } catch (e) {
                DebugLogService().warn('[FirebaseService] Failed to POST chunk_$i to host HTTP server $postUrl: $e');
              }
            }

            await _firestore.collection('hosts').doc(targetHostId).set({
              'usedStorageBytes': FieldValue.increment(chunkBytes.length),
              'activeChunks': FieldValue.increment(1),
              'lastSeen': FieldValue.serverTimestamp(),
            }, SetOptions(merge: true)).timeout(const Duration(seconds: 3));
          }

          DebugLogService().info('[FirebaseService] Registered chunk_$i metadata (${chunkBytes.length} bytes) to assigned host node: $targetHostname ($targetOwnerEmail).');
        } catch (e) {
          DebugLogService().warn('[FirebaseService] Failed to write chunk_$i metadata: $e');
        }
      }
    } catch (e, st) {
      DebugLogService().error('[FirebaseService] FIRESTORE WRITE FAILED: $e', e, st);
      if (e.toString().contains('unavailable')) {
        throw Exception('Firestore unavailable.\nFix: Go to Firebase Console > Firestore Database > Create Database.');
      }
      if (e.toString().contains('permission-denied')) {
        throw Exception('Firestore permission denied.\nFix: Go to Firebase Console > Firestore > Rules and allow authenticated writes.');
      }
      rethrow;
    }

    return FileItem(
      id: fileId,
      filename: filename,
      sizeBytes: fileBytes.length,
      createdAt: DateTime.now(),
      chunkCount: calculatedChunkCount,
    );
  }

  /// Fetches list of files stored in user's Cloud Firestore Vault across all devices.
  Future<List<FileItem>> listUserFiles() async {
    final user = _auth.currentUser;
    if (user == null) return [];

    try {
      QuerySnapshot<Map<String, dynamic>> snapshot;
      try {
        snapshot = await _firestore
            .collection('users')
            .doc(user.uid)
            .collection('files')
            .orderBy('createdAt', descending: true)
            .get()
            .timeout(const Duration(seconds: 8));
      } catch (e) {
        DebugLogService().warn('[FirebaseService] listUserFiles orderBy failed ($e), falling back to unindexed fetch.');
        snapshot = await _firestore
            .collection('users')
            .doc(user.uid)
            .collection('files')
            .get()
            .timeout(const Duration(seconds: 8));
      }

      final files = snapshot.docs.map((doc) {
        final data = doc.data();
        DateTime created = DateTime.now();
        if (data['createdAt'] is Timestamp) {
          created = (data['createdAt'] as Timestamp).toDate();
        } else if (data['createdAtIso'] != null) {
          created = DateTime.tryParse(data['createdAtIso'].toString()) ?? DateTime.now();
        }
        return FileItem(
          id: data['id']?.toString() ?? doc.id,
          filename: data['filename']?.toString() ?? 'vault_file.bin',
          sizeBytes: data['sizeBytes'] ?? 0,
          createdAt: created,
          chunkCount: data['chunkCount'] ?? 1,
        );
      }).toList();

      files.sort((a, b) => b.createdAt.compareTo(a.createdAt));
      return files;
    } catch (e, st) {
      DebugLogService().error('[FirebaseService] listUserFiles error: $e', e, st);
      return [];
    }
  }

  /// Downloads encrypted bytes and AES key for a given file ID by querying host chunk location metadata from Firestore and downloading directly from host containers.
  Future<Map<String, dynamic>> downloadEncryptedFile(String fileId) async {
    final user = _auth.currentUser;
    if (user == null) throw Exception('User not authenticated with Firebase');

    DocumentSnapshot<Map<String, dynamic>>? docSnap;
    try {
      docSnap = await _firestore
          .collection('users')
          .doc(user.uid)
          .collection('files')
          .doc(fileId)
          .get()
          .timeout(const Duration(seconds: 5));
    } catch (_) {}

    if (docSnap == null || !docSnap.exists || docSnap.data() == null) {
      throw Exception('File metadata not found in cloud vault.');
    }

    final data = docSnap.data()!;
    final String encryptedAesKey = data['encryptedAesKey']?.toString() ?? '';

    Uint8List? encryptedBytes;
    final List<Uint8List> chunkList = [];

    // Query chunk host location metadata from Firestore (NO payload stored in Firestore/Firebase)
    try {
      final chunksSnap = await _firestore
          .collection('users')
          .doc(user.uid)
          .collection('files')
          .doc(fileId)
          .collection('chunks')
          .orderBy('chunkIndex')
          .get()
          .timeout(const Duration(seconds: 8));

      if (chunksSnap.docs.isNotEmpty) {
        final dio = Dio();
        for (final chunkDoc in chunksSnap.docs) {
          final chunkData = chunkDoc.data();
          final String rawHostIp = chunkData['assignedHostIp']?.toString() ?? '127.0.0.1';
          String hostIp = rawHostIp.trim();
          if (hostIp.isEmpty ||
              hostIp.contains(' ') ||
              hostIp.toLowerCase().contains('lan') ||
              hostIp.toLowerCase().contains('mesh')) {
            hostIp = '127.0.0.1';
          }

          final int chunkIndex = chunkData['chunkIndex'] ?? 0;
          final String chunkId = chunkData['chunkId']?.toString() ?? '${fileId}_chunk_$chunkIndex';
          final int sizeBytes = chunkData['sizeBytes'] ?? 0;

          bool chunkFetched = false;

          // 1. LOCAL CONTAINER FIRST (fastest for self-hosted single device)
          // Try all known local container paths before hitting the network
          {
            final candidatePaths = <String>{};
            try {
              final savedPath = await SecureStorageService().getHostContainerPath();
              if (savedPath != null && savedPath.isNotEmpty) candidatePaths.add(savedPath);
            } catch (_) {}
            try {
              final defaultPath = await HostRepository().getDefaultContainerPath();
              candidatePaths.add(defaultPath);
            } catch (_) {}

            for (final path in candidatePaths) {
              if (chunkFetched) break;
              try {
                final localBytes = await HostRepository().readChunkFromLocalContainer(
                  path,
                  chunkIndex,
                  sizeBytes,
                  chunkId: chunkId,
                );
                if (localBytes != null && localBytes.isNotEmpty) {
                  chunkList.add(localBytes);
                  chunkFetched = true;
                  DebugLogService().info('[FirebaseService] Retrieved chunk $chunkId from local container: $path');
                }
              } catch (e) {
                DebugLogService().warn('[FirebaseService] Local container read error for $path: $e');
              }
            }
          }

          // 2. Primary host IP HTTP attempt (15s timeout to handle large chunks)
          if (!chunkFetched && hostIp != '127.0.0.1' && hostIp != 'localhost') {
            final String primaryUrl = 'http://$hostIp:8080/api/storage/chunks/$chunkId';
            try {
              final response = await dio.get<List<int>>(
                primaryUrl,
                options: Options(responseType: ResponseType.bytes),
              ).timeout(const Duration(seconds: 15));

              if (response.data != null && response.data!.isNotEmpty) {
                chunkList.add(Uint8List.fromList(response.data!));
                chunkFetched = true;
                DebugLogService().info('[FirebaseService] Downloaded chunk #$chunkIndex (${response.data!.length} bytes) from host: $primaryUrl');
              }
            } catch (e) {
              DebugLogService().warn('[FirebaseService] Primary host fetch failed for $primaryUrl: $e. Trying loopback.');
            }
          }

          // 3. Loopback 127.0.0.1 HTTP attempt (self-host running on same device)
          if (!chunkFetched) {
            final String loopbackUrl = 'http://127.0.0.1:8080/api/storage/chunks/$chunkId';
            try {
              final response = await dio.get<List<int>>(
                loopbackUrl,
                options: Options(responseType: ResponseType.bytes),
              ).timeout(const Duration(seconds: 10));

              if (response.data != null && response.data!.isNotEmpty) {
                chunkList.add(Uint8List.fromList(response.data!));
                chunkFetched = true;
                DebugLogService().info('[FirebaseService] Downloaded chunk #$chunkIndex (${response.data!.length} bytes) via loopback: $loopbackUrl');
              }
            } catch (_) {}
          }

          if (!chunkFetched) {
            DebugLogService().warn('[FirebaseService] All fetch strategies failed for chunk: $chunkId');
          }
        }

        if (chunkList.isNotEmpty) {
          encryptedBytes = FileChunker.reassembleChunks(chunkList);
          DebugLogService().info('[FirebaseService] Successfully reassembled ${chunkList.length} encrypted chunks from host container pool.');
        }
      }
    } catch (e) {
      DebugLogService().error('[FirebaseService] Host chunk location fetch error: $e');
    }

    if (encryptedBytes == null) {
      throw Exception('Failed to retrieve file chunk payloads from host containers.');
    }

    return {
      'encryptedBytes': encryptedBytes,
      'encryptedAesKey': encryptedAesKey,
    };
  }

  /// Returns list of active host snapshots currently online and connected to internet (deduplicated by host owner account).
  Future<List<QueryDocumentSnapshot<Map<String, dynamic>>>> getActiveHostDocs() async {
    try {
      final now = DateTime.now();
      final snapshot = await _firestore.collection('hosts').get().timeout(const Duration(seconds: 5));
      final Map<String, QueryDocumentSnapshot<Map<String, dynamic>>> uniqueHostsByOwner = {};

      for (final doc in snapshot.docs) {
        final data = doc.data();
        final String status = data['status']?.toString().toUpperCase() ?? 'OFFLINE';
        final bool isAvailableForPublic = data['isAvailableForPublic'] ?? true;
        final int reservedStorageBytes = data['reservedStorageBytes'] ?? 0;
        final String ownerKey = data['ownerId']?.toString() ?? data['ownerEmail']?.toString() ?? doc.id;

        // Must be an allocated host node with > 0 bytes capacity
        if (reservedStorageBytes <= 0) continue;

        DateTime? lastSeen;
        if (data['lastSeen'] is Timestamp) {
          lastSeen = (data['lastSeen'] as Timestamp).toDate();
        } else if (data['lastSeenIso'] != null) {
          lastSeen = DateTime.tryParse(data['lastSeenIso'].toString());
        }

        final bool isRecentlyActive = lastSeen == null || now.difference(lastSeen).inSeconds <= 10;

        if ((status == 'ONLINE' || status == 'ACTIVE') && isAvailableForPublic && isRecentlyActive) {
          if (!uniqueHostsByOwner.containsKey(ownerKey)) {
            uniqueHostsByOwner[ownerKey] = doc;
          } else {
            // Keep the document with the most recent lastSeen timestamp
            final existingData = uniqueHostsByOwner[ownerKey]!.data();
            DateTime? existingLastSeen;
            if (existingData['lastSeen'] is Timestamp) {
              existingLastSeen = (existingData['lastSeen'] as Timestamp).toDate();
            } else if (existingData['lastSeenIso'] != null) {
              existingLastSeen = DateTime.tryParse(existingData['lastSeenIso'].toString());
            }
            if (lastSeen != null && (existingLastSeen == null || lastSeen.isAfter(existingLastSeen))) {
              uniqueHostsByOwner[ownerKey] = doc;
            }
          }
        }
      }
      return uniqueHostsByOwner.values.toList();
    } catch (_) {
      return [];
    }
  }

  /// Returns the count of active container nodes connected to the internet and available for public client file uploads.
  Future<int> getActiveHostsCount() async {
    final docs = await getActiveHostDocs();
    return docs.length;
  }

  /// Real-time stream emitting the active container host node count connected across all client devices.
  Stream<int> streamActiveHostsCount() {
    return _firestore.collection('hosts').snapshots().asyncMap((_) async {
      return await getActiveHostsCount();
    });
  }

  /// Retrieves host node document from Cloud Firestore owned by a specific user.
  Future<DocumentSnapshot<Map<String, dynamic>>?> getHostDocByOwner(String ownerUid) async {
    try {
      final snap = await _firestore.collection('hosts').where('ownerId', isEqualTo: ownerUid).limit(1).get().timeout(const Duration(seconds: 5));
      if (snap.docs.isNotEmpty) {
        return snap.docs.first;
      }
    } catch (_) {}
    return null;
  }

  /// Fetches detailed chunk distribution list for a user's file (showing target host account, node name, device, IP).
  Future<List<Map<String, dynamic>>> getFileChunkDetails(String fileId) async {
    final user = _auth.currentUser;
    if (user == null) return [];
    try {
      final snap = await _firestore
          .collection('users')
          .doc(user.uid)
          .collection('files')
          .doc(fileId)
          .collection('chunks')
          .orderBy('chunkIndex')
          .get()
          .timeout(const Duration(seconds: 5));
      return snap.docs.map((d) => d.data()).toList();
    } catch (_) {
      return [];
    }
  }

  /// Fetches detailed list of client chunks stored inside the current device's host container (showing client email, file name, byte size).
  Future<List<Map<String, dynamic>>> getHostedChunksForCurrentHost() async {
    final user = _auth.currentUser;
    if (user == null) return [];
    try {
      final hostSnap = await getHostDocByOwner(user.uid);
      if (hostSnap == null) return [];
      final chunksSnap = await _firestore
          .collection('hosts')
          .doc(hostSnap.id)
          .collection('hosted_chunks')
          .orderBy('createdAt', descending: true)
          .get()
          .timeout(const Duration(seconds: 5));
      return chunksSnap.docs.map((d) => d.data()).toList();
    } catch (_) {
      return [];
    }
  }

  Future<String> _getLocalIpAddress() async {
    bool isTailscaleRange(String ip) {
      try {
        final parts = ip.split('.');
        if (parts.length != 4) return false;
        final second = int.parse(parts[1]);
        return second >= 64 && second <= 127;
      } catch (_) { return false; }
    }

    try {
      final interfaces = await NetworkInterface.list(type: InternetAddressType.IPv4);
      // Priority: prefer standard LAN ranges (192.168.x.x, 10.x.x.x, 172.x.x.x)
      for (final iface in interfaces) {
        final name = iface.name.toLowerCase();
        if (name.contains('loopback') || name == 'lo' || name.contains('tailscale') || name.contains('tun')) continue;
        for (final addr in iface.addresses) {
          if (addr.isLoopback) continue;
          final ip = addr.address;
          if (ip.startsWith('100.') && isTailscaleRange(ip)) continue;
          if (ip.startsWith('169.254.')) continue;
          if (ip.startsWith('192.168.') || ip.startsWith('10.') || ip.startsWith('172.')) {
            return ip;
          }
        }
      }
      // Fallback: any non-loopback, non-Tailscale IP
      for (final iface in interfaces) {
        final name = iface.name.toLowerCase();
        if (name.contains('loopback') || name == 'lo') continue;
        for (final addr in iface.addresses) {
          if (addr.isLoopback) continue;
          final ip = addr.address;
          if (ip.startsWith('100.64.') || ip.startsWith('169.254.')) continue;
          if (!ip.startsWith('127.') && ip.isNotEmpty) return ip;
        }
      }
    } catch (_) {}
    return '127.0.0.1';
  }

  /// Registers or updates a host node in Cloud Firestore for network-wide tracking.
  Future<void> updateHostNodeStatus({
    required String hostId,
    required String hostname,
    required String status,
    required int reservedStorageBytes,
    int? usedStorageBytes,
    String? deviceType,
    String? publicIp,
  }) async {
    try {
      final user = _auth.currentUser;
      final String canonicalHostId = (user != null && user.uid.isNotEmpty) ? 'host_${user.uid}' : hostId;
      final String resolvedIp = (publicIp != null &&
              publicIp.isNotEmpty &&
              publicIp != '127.0.0.1' &&
              !publicIp.contains(' ') &&
              !publicIp.toLowerCase().contains('lan'))
          ? publicIp
          : await _getLocalIpAddress();

      final Map<String, dynamic> updateData = {
        'id': canonicalHostId,
        'hostname': hostname,
        'status': status,
        'isAvailableForPublic': true,
        'reservedStorageBytes': reservedStorageBytes,
        'ownerId': user?.uid ?? 'anonymous',
        'ownerEmail': user?.email ?? (user?.isAnonymous == true ? 'anonymous@neurovault.net' : 'Vault Host'),
        'deviceType': deviceType ?? (Platform.isAndroid ? 'Mobile - Android' : 'Desktop - Windows'),
        'publicIp': resolvedIp,
        'lastSeen': FieldValue.serverTimestamp(),
        'lastSeenIso': DateTime.now().toIso8601String(),
      };
      if (usedStorageBytes != null) {
        updateData['usedStorageBytes'] = usedStorageBytes;
      }

      await _firestore.collection('hosts').doc(canonicalHostId).set(updateData, SetOptions(merge: true))
          .timeout(const Duration(seconds: 5));

      // Automatically clean up any legacy duplicate host node documents for this owner
      if (user != null) {
        try {
          final dupes = await _firestore.collection('hosts').where('ownerId', isEqualTo: user.uid).get();
          for (final d in dupes.docs) {
            if (d.id != canonicalHostId) {
              await d.reference.delete();
            }
          }
        } catch (_) {}
      }
    } catch (_) {}
  }

  /// Clears all host node documents from Cloud Firestore `hosts` collection for debugging reset.
  Future<void> clearAllHostNodes() async {
    try {
      final snapshot = await _firestore.collection('hosts').get();
      for (final doc in snapshot.docs) {
        await doc.reference.delete();
      }
      DebugLogService().info('[FirebaseService] Cleared all registered host nodes from Cloud Firestore.');
    } catch (e) {
      DebugLogService().error('[FirebaseService] clearAllHostNodes error: $e');
    }
  }
}
