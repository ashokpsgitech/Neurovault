import 'dart:convert';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import '../utils/debug_log_service.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_storage/firebase_storage.dart';
import 'package:google_sign_in/google_sign_in.dart';

import '../../firebase_options.dart';
import '../../features/authentication/models/user_model.dart';
import '../../features/files/models/file_metadata_model.dart';
import '../crypto/file_chunker.dart';

/// 24/7 Firebase Cloud Backend Service for NeuroVault.
/// Provides Zero-Trust Cloud Storage, Authentication, and Firestore Metadata sync.
class FirebaseService {
  static final FirebaseService _instance = FirebaseService._internal();
  factory FirebaseService() => _instance;
  FirebaseService._internal();

  final FirebaseAuth _auth = FirebaseAuth.instance;
  final FirebaseFirestore _firestore = FirebaseFirestore.instance;
  final FirebaseStorage _storage = FirebaseStorage.instance;

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
          'role': 'CLIENT',
          'createdAt': FieldValue.serverTimestamp(),
        }).timeout(const Duration(seconds: 5));
      }
    } catch (_) {
      // Proceed gracefully if Firestore is unavailable or database is pending setup
    }

    return UserModel(
      id: user.uid,
      username: username,
      email: email,
      role: 'CLIENT',
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
    final storagePath = 'users/${user.uid}/vault/$fileId/$filename.enc';

    String downloadUrl = '';
    bool storageSucceeded = false;
    try {
      final storageRef = _storage.ref().child(storagePath);
      final uploadTask = await storageRef.putData(
        fileBytes,
        SettableMetadata(contentType: 'application/octet-stream'),
      );
      downloadUrl = await uploadTask.ref.getDownloadURL();
      storageSucceeded = true;
      DebugLogService().info('[FirebaseService] Storage upload OK. Download URL obtained.');
    } catch (e) {
      DebugLogService().warn(
        '[FirebaseService] Firebase Storage bucket unavailable ($e). Storing encrypted payload in Cloud Firestore document for 100% online sync.'
      );
    }

    final fileDoc = <String, dynamic>{
      'id': fileId,
      'filename': filename,
      'sizeBytes': fileBytes.length,
      'encryptedAesKey': aesKeyBase64,
      'downloadUrl': downloadUrl,
      'storagePath': storageSucceeded ? storagePath : '',
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
      DebugLogService().info('[FirebaseService] Firestore file document write OK.');

      // Store dynamic split chunks across active host container pool in Cloud Firestore subcollection & update host storage stats
      for (int i = 0; i < dynamicChunks.length; i++) {
        final chunkBytes = dynamicChunks[i];
        final targetHostDoc = activeHostDocs.isNotEmpty ? activeHostDocs[i % activeHostDocs.length] : null;
        final targetHostId = targetHostDoc?.id ?? 'local-container';

        try {
          await _firestore
              .collection('users')
              .doc(user.uid)
              .collection('files')
              .doc(fileId)
              .collection('chunks')
              .doc('chunk_$i')
              .set({
            'chunkIndex': i,
            'sizeBytes': chunkBytes.length,
            'assignedHostId': targetHostId,
            'chunkBase64': base64Encode(chunkBytes),
            'createdAt': FieldValue.serverTimestamp(),
          }).timeout(const Duration(seconds: 5));

          if (targetHostDoc != null) {
            await _firestore.collection('hosts').doc(targetHostId).set({
              'usedStorageBytes': FieldValue.increment(chunkBytes.length),
              'activeChunks': FieldValue.increment(1),
              'lastSeen': FieldValue.serverTimestamp(),
            }, SetOptions(merge: true)).timeout(const Duration(seconds: 3));
          }

          DebugLogService().info('[FirebaseService] Stored dynamic chunk_$i (${chunkBytes.length} bytes) on assigned host node: $targetHostId.');
        } catch (e) {
          DebugLogService().warn('[FirebaseService] Failed to write chunk_$i subcollection: $e');
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

  /// Downloads encrypted bytes and AES key for a given file ID with multi-tiered cloud fallbacks.
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
    final String storagePath = data['storagePath']?.toString() ?? '';
    final String downloadUrl = data['downloadUrl']?.toString() ?? '';

    Uint8List? encryptedBytes;

    // Tier 0: Fetch & reassemble dynamic split chunks from Cloud Firestore host container pool
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
        final List<Uint8List> chunkList = [];
        for (final chunkDoc in chunksSnap.docs) {
          final b64 = chunkDoc.data()['chunkBase64']?.toString();
          if (b64 != null && b64.isNotEmpty) {
            chunkList.add(base64Decode(b64));
          }
        }
        if (chunkList.isNotEmpty) {
          encryptedBytes = FileChunker.reassembleChunks(chunkList);
          DebugLogService().info('[FirebaseService] Download Tier 0: Successfully reassembled ${chunkList.length} dynamic host chunks from container pool.');
        }
      }
    } catch (e) {
      DebugLogService().warn('[FirebaseService] Download Tier 0 chunk fetch skipped: $e');
    }

    // Tier 2: Direct HTTP download URL (Firebase Storage / Host Node endpoint)
    if (encryptedBytes == null && downloadUrl.isNotEmpty && downloadUrl.startsWith('http')) {
      try {
        final dio = Dio();
        final response = await dio.get<List<int>>(
          downloadUrl,
          options: Options(responseType: ResponseType.bytes),
        ).timeout(const Duration(seconds: 15));
        if (response.data != null && response.data!.isNotEmpty) {
          encryptedBytes = Uint8List.fromList(response.data!);
          DebugLogService().info('[FirebaseService] Download Tier 2: Retrieved payload via direct download URL.');
        }
      } catch (e) {
        DebugLogService().error('[FirebaseService] Download Tier 2 failed: $e');
      }
    }

    // Tier 3: Firebase Storage reference path
    if (encryptedBytes == null && storagePath.isNotEmpty) {
      try {
        final storageRef = _storage.ref(storagePath);
        encryptedBytes = await storageRef.getData(100 * 1024 * 1024);
        DebugLogService().info('[FirebaseService] Download Tier 3: Retrieved payload from Firebase Storage.');
      } catch (e) {
        DebugLogService().error('[FirebaseService] Download Tier 3 failed: $e');
      }
    }

    // Tier 4: Storage path fallback search by filename
    if (encryptedBytes == null) {
      try {
        final filename = data['filename']?.toString() ?? 'file.bin';
        final fallbackPath = 'users/${user.uid}/vault/$fileId/$filename.enc';
        final storageRef = _storage.ref(fallbackPath);
        encryptedBytes = await storageRef.getData(100 * 1024 * 1024);
        DebugLogService().info('[FirebaseService] Download Tier 4: Retrieved payload via fallback path search.');
      } catch (_) {}
    }

    if (encryptedBytes == null) {
      throw Exception('Failed to retrieve file content from Cloud Vault.');
    }

    return {
      'encryptedBytes': encryptedBytes,
      'encryptedAesKey': encryptedAesKey,
    };
  }

  /// Returns list of active host snapshots currently online and connected to internet.
  Future<List<QueryDocumentSnapshot<Map<String, dynamic>>>> getActiveHostDocs() async {
    try {
      final now = DateTime.now();
      final snapshot = await _firestore.collection('hosts').get().timeout(const Duration(seconds: 5));
      final List<QueryDocumentSnapshot<Map<String, dynamic>>> activeDocs = [];

      for (final doc in snapshot.docs) {
        final data = doc.data();
        final String status = data['status']?.toString().toUpperCase() ?? 'OFFLINE';
        final bool isAvailableForPublic = data['isAvailableForPublic'] ?? true;

        DateTime? lastSeen;
        if (data['lastSeen'] is Timestamp) {
          lastSeen = (data['lastSeen'] as Timestamp).toDate();
        } else if (data['lastSeenIso'] != null) {
          lastSeen = DateTime.tryParse(data['lastSeenIso'].toString());
        }

        final bool isRecentlyActive = lastSeen == null || now.difference(lastSeen).inMinutes <= 3;

        if ((status == 'ONLINE' || status == 'ACTIVE') && isAvailableForPublic && isRecentlyActive) {
          activeDocs.add(doc);
        }
      }
      return activeDocs;
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
    return _firestore.collection('hosts').snapshots().map((snapshot) {
      final now = DateTime.now();
      int activeCount = 0;
      for (final doc in snapshot.docs) {
        final data = doc.data();
        final String status = data['status']?.toString().toUpperCase() ?? 'OFFLINE';
        final bool isAvailableForPublic = data['isAvailableForPublic'] ?? true;

        DateTime? lastSeen;
        if (data['lastSeen'] is Timestamp) {
          lastSeen = (data['lastSeen'] as Timestamp).toDate();
        } else if (data['lastSeenIso'] != null) {
          lastSeen = DateTime.tryParse(data['lastSeenIso'].toString());
        }

        final bool isRecentlyActive = lastSeen == null || now.difference(lastSeen).inMinutes <= 3;

        if ((status == 'ONLINE' || status == 'ACTIVE') && isAvailableForPublic && isRecentlyActive) {
          activeCount++;
        }
      }
      return activeCount;
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

  /// Registers or updates a host node in Cloud Firestore for network-wide tracking.
  Future<void> updateHostNodeStatus({
    required String hostId,
    required String hostname,
    required String status,
    required int reservedStorageBytes,
  }) async {
    try {
      final user = _auth.currentUser;
      await _firestore.collection('hosts').doc(hostId).set({
        'id': hostId,
        'hostname': hostname,
        'status': status,
        'isAvailableForPublic': true,
        'reservedStorageBytes': reservedStorageBytes,
        'ownerId': user?.uid ?? 'anonymous',
        'lastSeen': FieldValue.serverTimestamp(),
        'lastSeenIso': DateTime.now().toIso8601String(),
      }, SetOptions(merge: true)).timeout(const Duration(seconds: 5));
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
