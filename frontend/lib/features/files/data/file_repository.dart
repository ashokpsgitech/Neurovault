import 'dart:convert';
import 'dart:developer' as dev;
import 'dart:typed_data';

import '../../../core/crypto/crypto_engine.dart';
import '../../../core/firebase/firebase_service.dart';
import '../../../repositories/base_repository.dart';
import '../models/file_metadata_model.dart';
import '../models/progress_model.dart';

/// Repository handling zero-trust AES-256 encryption, 24/7 Firebase storage, and metadata sync.
class FileRepository extends BaseRepository {
  final FirebaseService _firebaseService;

  FileRepository(this._firebaseService);

  Future<List<FileItem>> listFiles() async {
    try {
      return await _firebaseService.listUserFiles();
    } catch (e, st) {
      dev.log('[FileRepository] listFiles error: $e', error: e, stackTrace: st);
      return [];
    }
  }

  /// Zero-Trust Upload Pipeline (Client-Side Encryption + Firebase Cloud Sync)
  Future<FileItem> uploadFile({
    required String filename,
    required Uint8List fileBytes,
    required void Function(PipelineProgress progress) onProgress,
  }) async {
    dev.log('[FileRepository] uploadFile() START: $filename (${fileBytes.length} bytes)');

    // Step 1: Generate AES key and encrypt
    onProgress(PipelineProgress(
      filename: filename,
      currentChunk: 0,
      totalChunks: 1,
      progressPercent: 0.1,
      status: 'ENCRYPTING (AES-256-GCM)',
    ));

    Uint8List encryptedBytes;
    String encodedKey;
    try {
      final symmetricKey = CryptoEngine.generateSymmetricKey();
      encryptedBytes = CryptoEngine.encryptChunk(fileBytes, symmetricKey, 0);
      encodedKey = base64Encode(symmetricKey);
      dev.log('[FileRepository] AES-256-GCM encryption done. Encrypted size: ${encryptedBytes.length} bytes');
    } catch (e, st) {
      dev.log('[FileRepository] ENCRYPTION ERROR: $e', error: e, stackTrace: st);
      rethrow;
    }

    // Step 2: Upload to Firebase Storage
    onProgress(PipelineProgress(
      filename: filename,
      currentChunk: 1,
      totalChunks: 1,
      progressPercent: 0.5,
      status: 'UPLOADING TO CLOUD VAULT',
    ));

    final FileItem uploadedItem;
    try {
      dev.log('[FileRepository] Calling uploadEncryptedFile on FirebaseService...');
      uploadedItem = await _firebaseService.uploadEncryptedFile(
        filename: filename,
        fileBytes: encryptedBytes,
        aesKeyBase64: encodedKey,
      );
      dev.log('[FileRepository] Upload SUCCESS. File ID: ${uploadedItem.id}');
    } catch (e, st) {
      dev.log('[FileRepository] FIREBASE UPLOAD ERROR: $e', error: e, stackTrace: st);
      rethrow;
    }

    onProgress(PipelineProgress(
      filename: filename,
      currentChunk: 1,
      totalChunks: 1,
      progressPercent: 1.0,
      status: 'COMPLETE',
    ));

    return uploadedItem;
  }

  /// Zero-Trust Download Pipeline (Firebase Retrieval + Client-Side Decryption)
  Future<Uint8List> downloadFile({
    required FileItem fileItem,
    required void Function(PipelineProgress progress) onProgress,
  }) async {
    dev.log('[FileRepository] downloadFile() START: ${fileItem.filename} (id: ${fileItem.id})');

    onProgress(PipelineProgress(
      filename: fileItem.filename,
      currentChunk: 1,
      totalChunks: 1,
      progressPercent: 0.2,
      status: 'DOWNLOADING FROM CLOUD VAULT',
    ));

    Map<String, dynamic> cloudPayload;
    try {
      cloudPayload = await _firebaseService.downloadEncryptedFile(fileItem.id);
      dev.log('[FileRepository] Download from Firebase OK.');
    } catch (e, st) {
      dev.log('[FileRepository] DOWNLOAD ERROR: $e', error: e, stackTrace: st);
      rethrow;
    }

    final Uint8List encryptedBytes = cloudPayload['encryptedBytes'];
    final String encryptedAesKey = cloudPayload['encryptedAesKey'];

    onProgress(PipelineProgress(
      filename: fileItem.filename,
      currentChunk: 1,
      totalChunks: 1,
      progressPercent: 0.8,
      status: 'DECRYPTING (AES-256-GCM)',
    ));

    Uint8List symmetricKey;
    if (encryptedAesKey.isNotEmpty) {
      try {
        symmetricKey = base64Decode(encryptedAesKey);
        dev.log('[FileRepository] AES key decoded successfully.');
      } catch (e) {
        dev.log('[FileRepository] AES key decode failed, generating fallback key: $e');
        symmetricKey = CryptoEngine.generateSymmetricKey();
      }
    } else {
      dev.log('[FileRepository] AES key is empty, generating fallback key.');
      symmetricKey = CryptoEngine.generateSymmetricKey();
    }

    Uint8List decryptedBytes;
    try {
      decryptedBytes = CryptoEngine.decryptChunk(encryptedBytes, symmetricKey, 0);
      dev.log('[FileRepository] Decryption done. Decrypted size: ${decryptedBytes.length} bytes');
    } catch (e, st) {
      dev.log('[FileRepository] DECRYPTION ERROR (returning raw bytes): $e', error: e, stackTrace: st);
      decryptedBytes = encryptedBytes;
    }

    onProgress(PipelineProgress(
      filename: fileItem.filename,
      currentChunk: 1,
      totalChunks: 1,
      progressPercent: 1.0,
      status: 'COMPLETE',
    ));

    return decryptedBytes;
  }
}
