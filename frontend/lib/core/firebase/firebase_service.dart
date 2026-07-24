import 'dart:typed_data';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_storage/firebase_storage.dart';
import 'package:google_sign_in/google_sign_in.dart';

import '../../firebase_options.dart';
import '../../features/authentication/models/user_model.dart';
import '../../features/files/models/file_metadata_model.dart';

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
    } catch (_) {}
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

    final docSnap = await _firestore.collection('users').doc(user.uid).get();
    if (!docSnap.exists) {
      await _firestore.collection('users').doc(user.uid).set({
        'id': user.uid,
        'username': username,
        'email': email,
        'role': 'CLIENT',
        'createdAt': FieldValue.serverTimestamp(),
      });
    }

    return UserModel(
      id: user.uid,
      username: username,
      email: email,
      role: 'CLIENT',
    );
  }

  /// Registers user with Email & Password on Firebase Auth.
  Future<UserModel> register({
    required String username,
    required String email,
    required String password,
  }) async {
    final credential = await _auth.createUserWithEmailAndPassword(
      email: email,
      password: password,
    );

    final user = credential.user;
    if (user == null) {
      throw Exception('Failed to create user account');
    }

    await user.updateDisplayName(username);

    final userDoc = {
      'id': user.uid,
      'username': username,
      'email': email,
      'role': 'CLIENT',
      'createdAt': FieldValue.serverTimestamp(),
    };

    await _firestore.collection('users').doc(user.uid).set(userDoc);

    return UserModel(
      id: user.uid,
      username: username,
      email: email,
      role: 'CLIENT',
    );
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

    final docSnap = await _firestore.collection('users').doc(user.uid).get();

    if (docSnap.exists && docSnap.data() != null) {
      final data = docSnap.data()!;
      return UserModel(
        id: user.uid,
        username: data['username']?.toString() ?? user.displayName ?? email.split('@').first,
        email: email,
        role: data['role']?.toString() ?? 'CLIENT',
      );
    } else {
      final fallbackUsername = user.displayName ?? email.split('@').first;
      await _firestore.collection('users').doc(user.uid).set({
        'id': user.uid,
        'username': fallbackUsername,
        'email': email,
        'role': 'CLIENT',
        'createdAt': FieldValue.serverTimestamp(),
      });
      return UserModel(
        id: user.uid,
        username: fallbackUsername,
        email: email,
        role: 'CLIENT',
      );
    }
  }

  /// Fetches current user profile from Firebase Auth & Firestore.
  Future<UserModel?> getCurrentUser() async {
    final user = _auth.currentUser;
    if (user == null) return null;

    try {
      final docSnap = await _firestore.collection('users').doc(user.uid).get();
      if (docSnap.exists && docSnap.data() != null) {
        final data = docSnap.data()!;
        return UserModel(
          id: user.uid,
          username: data['username']?.toString() ?? user.displayName ?? user.email?.split('@').first ?? 'User',
          email: user.email ?? '',
          role: data['role']?.toString() ?? 'CLIENT',
        );
      }
    } catch (_) {}

    return UserModel(
      id: user.uid,
      username: user.displayName ?? user.email?.split('@').first ?? 'User',
      email: user.email ?? '',
      role: 'CLIENT',
    );
  }

  /// Logs out user from Firebase Auth.
  Future<void> logout() async {
    await _auth.signOut();
  }

  /// Uploads encrypted file bytes to Firebase Storage & saves metadata in Firestore.
  Future<FileItem> uploadEncryptedFile({
    required String filename,
    required Uint8List fileBytes,
    required String aesKeyBase64,
  }) async {
    final user = _auth.currentUser;
    if (user == null) throw Exception('User not authenticated with Firebase');

    final fileId = DateTime.now().millisecondsSinceEpoch.toString();
    final storageRef = _storage.ref().child('users/${user.uid}/vault/$fileId/$filename.enc');

    final uploadTask = await storageRef.putData(
      fileBytes,
      SettableMetadata(contentType: 'application/octet-stream'),
    );
    final downloadUrl = await uploadTask.ref.getDownloadURL();

    final fileDoc = {
      'id': fileId,
      'filename': filename,
      'sizeBytes': fileBytes.length,
      'encryptedAesKey': aesKeyBase64,
      'downloadUrl': downloadUrl,
      'storagePath': storageRef.fullPath,
      'ownerId': user.uid,
      'createdAt': FieldValue.serverTimestamp(),
      'chunkCount': 1,
    };

    await _firestore
        .collection('users')
        .doc(user.uid)
        .collection('files')
        .doc(fileId)
        .set(fileDoc);

    return FileItem(
      id: fileId,
      filename: filename,
      sizeBytes: fileBytes.length,
      createdAt: DateTime.now(),
      chunkCount: 1,
    );
  }

  /// Fetches list of files stored in user's Cloud Firestore Vault.
  Future<List<FileItem>> listUserFiles() async {
    final user = _auth.currentUser;
    if (user == null) return [];

    final snapshot = await _firestore
        .collection('users')
        .doc(user.uid)
        .collection('files')
        .orderBy('createdAt', descending: true)
        .get();

    return snapshot.docs.map((doc) {
      final data = doc.data();
      return FileItem(
        id: data['id']?.toString() ?? doc.id,
        filename: data['filename']?.toString() ?? 'vault_file.bin',
        sizeBytes: data['sizeBytes'] ?? 0,
        createdAt: data['createdAt'] != null && data['createdAt'] is Timestamp
            ? (data['createdAt'] as Timestamp).toDate()
            : DateTime.now(),
        chunkCount: data['chunkCount'] ?? 1,
      );
    }).toList();
  }

  /// Downloads encrypted bytes and AES key for a given file ID.
  Future<Map<String, dynamic>> downloadEncryptedFile(String fileId) async {
    final user = _auth.currentUser;
    if (user == null) throw Exception('User not authenticated with Firebase');

    final docSnap = await _firestore
        .collection('users')
        .doc(user.uid)
        .collection('files')
        .doc(fileId)
        .get();

    if (!docSnap.exists || docSnap.data() == null) {
      throw Exception('File metadata not found in cloud vault.');
    }

    final data = docSnap.data()!;
    final String encryptedAesKey = data['encryptedAesKey']?.toString() ?? '';
    final String storagePath = data['storagePath']?.toString() ?? '';

    if (storagePath.isEmpty) {
      throw Exception('Invalid storage location');
    }

    final storageRef = _storage.ref(storagePath);
    // 100 MB max size download limit per file
    final Uint8List? encryptedBytes = await storageRef.getData(100 * 1024 * 1024);

    if (encryptedBytes == null) {
      throw Exception('Failed to retrieve file content from Cloud Storage');
    }

    return {
      'encryptedBytes': encryptedBytes,
      'encryptedAesKey': encryptedAesKey,
    };
  }
}
