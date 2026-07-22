import 'dart:typed_data';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_storage/firebase_storage.dart';
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

  /// Ensures Firebase App is initialized.
  static Future<void> initialize() async {
    try {
      await Firebase.initializeApp();
    } catch (_) {
      // Handled if initialized via firebase_options.dart
    }
  }

  /// Registers user with Email & Password on Firebase Auth.
  Future<UserCredential> register(String email, String password) async {
    final credential = await _auth.createUserWithEmailAndPassword(
      email: email,
      password: password,
    );
    await _firestore.collection('users').doc(credential.user!.uid).set({
      'email': email,
      'createdAt': FieldValue.serverTimestamp(),
    });
    return credential;
  }

  /// Logs in user with Email & Password on Firebase Auth.
  Future<UserCredential> login(String email, String password) async {
    return await _auth.signInWithEmailAndPassword(
      email: email,
      password: password,
    );
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
    final storageRef = _storage.ref().child('users/${user.uid}/vault/$fileId/$filename');

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
        createdAt: data['createdAt'] != null
            ? (data['createdAt'] as Timestamp).toDate()
            : DateTime.now(),
        chunkCount: data['chunkCount'] ?? 1,
      );
    }).toList();
  }
}
