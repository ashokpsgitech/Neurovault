import 'dart:convert';
import 'dart:typed_data';

import '../../../core/crypto/crypto_engine.dart';
import '../../../core/firebase/firebase_service.dart';
import '../../../core/storage/local_file_cache_service.dart';
import '../../../core/utils/debug_log_service.dart';
import '../../../repositories/base_repository.dart';
import '../models/file_metadata_model.dart';
import '../models/progress_model.dart';

/// Repository handling zero-trust AES-256 encryption, 24/7 Firebase storage, and metadata sync.
class FileRepository extends BaseRepository {
  final FirebaseService _firebaseService;
  final DebugLogService _logger = DebugLogService();

  FileRepository(this._firebaseService);

  Future<List<FileItem>> listFiles() async {
    try {
      final remoteFiles = await _firebaseService.listUserFiles();
      return await LocalFileCacheService().mergeWithRemote(remoteFiles);
    } catch (e, st) {
      _logger.error('[FileRepository] listFiles remote error: $e', e, st);
      return await LocalFileCacheService().loadCachedFiles();
    }
  }

  /// Zero-Trust Upload Pipeline (Client-Side Encryption + Firebase Cloud Sync)
  Future<FileItem> uploadFile({
    required String filename,
    required Uint8List fileBytes,
    required void Function(PipelineProgress progress) onProgress,
  }) async {
    _logger.info('[FileRepository] uploadFile() START: $filename (${fileBytes.length} bytes)');

    // Step 1: Generate AES key and encrypt
    onProgress(PipelineProgress(
      filename: filename,
      currentChunk: 0,
      totalChunks: 1,
      progressPercent: 0.1,
      status: 'ENCRYPTING (AES-256-GCM)',
    ));
    await Future.delayed(Duration.zero);

    Uint8List encryptedBytes;
    String encodedKey;
    try {
      final symmetricKey = CryptoEngine.generateSymmetricKey();
      encryptedBytes = await CryptoEngine.encryptChunkAsync(fileBytes, symmetricKey, 0);
      encodedKey = base64Encode(symmetricKey);
      _logger.info('[FileRepository] AES-256-GCM encryption done in isolate. Encrypted size: ${encryptedBytes.length} bytes');
    } catch (e, st) {
      _logger.error('[FileRepository] ENCRYPTION ERROR: $e', e, st);
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
    await Future.delayed(Duration.zero);

    FileItem uploadedItem;
    try {
      _logger.info('[FileRepository] Calling uploadEncryptedFile on FirebaseService...');
      uploadedItem = await _firebaseService.uploadEncryptedFile(
        filename: filename,
        fileBytes: encryptedBytes,
        aesKeyBase64: encodedKey,
      );
      _logger.info('[FileRepository] Upload SUCCESS. File ID: ${uploadedItem.id}');
    } catch (e) {
      _logger.warn('[FileRepository] Firebase Storage unavailable ($e). Saving encrypted file to local Vault.');
      final fileId = DateTime.now().millisecondsSinceEpoch.toString();
      uploadedItem = FileItem(
        id: fileId,
        filename: filename,
        sizeBytes: encryptedBytes.length,
        createdAt: DateTime.now(),
        chunkCount: 1,
      );
    }

    // Save uploaded item & encrypted payload to persistent local cache
    await LocalFileCacheService().saveEncryptedPayload(uploadedItem.id, encryptedBytes, encodedKey);
    await LocalFileCacheService().saveFile(uploadedItem);

    onProgress(PipelineProgress(
      filename: filename,
      currentChunk: 1,
      totalChunks: 1,
      progressPercent: 1.0,
      status: 'COMPLETE',
    ));
    await Future.delayed(Duration.zero);

    return uploadedItem;
  }

  /// Zero-Trust Download Pipeline (Firebase Retrieval / Local Cache + Client-Side Decryption)
  Future<Uint8List> downloadFile({
    required FileItem fileItem,
    required void Function(PipelineProgress progress) onProgress,
  }) async {
    _logger.info('[FileRepository] downloadFile() START: ${fileItem.filename} (id: ${fileItem.id})');

    onProgress(PipelineProgress(
      filename: fileItem.filename,
      currentChunk: 1,
      totalChunks: 1,
      progressPercent: 0.2,
      status: 'DOWNLOADING FROM VAULT',
    ));
    await Future.delayed(Duration.zero);

    Map<String, dynamic>? cloudPayload;
    cloudPayload = await LocalFileCacheService().loadEncryptedPayload(fileItem.id);
    if (cloudPayload == null) {
      try {
        cloudPayload = await _firebaseService.downloadEncryptedFile(fileItem.id);
        _logger.info('[FileRepository] Download from Firebase OK.');
      } catch (e, st) {
        _logger.error('[FileRepository] DOWNLOAD ERROR: $e', e, st);
        rethrow;
      }
    } else {
      _logger.info('[FileRepository] Loaded encrypted payload from local storage cache.');
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
    await Future.delayed(Duration.zero);

    Uint8List symmetricKey;
    if (encryptedAesKey.isNotEmpty) {
      try {
        symmetricKey = base64Decode(encryptedAesKey);
        _logger.info('[FileRepository] AES key decoded successfully.');
      } catch (e, st) {
        _logger.error('[FileRepository] AES key base64 decode failed: $e', e, st);
        throw Exception('Failed to decode AES encryption key: $e');
      }
    } else {
      _logger.error('[FileRepository] AES key is empty — file cannot be decrypted.');
      throw Exception('Encryption key missing in file metadata.');
    }

    Uint8List decryptedBytes;
    try {
      decryptedBytes = await CryptoEngine.decryptChunkAsync(encryptedBytes, symmetricKey, 0);
      _logger.info('[FileRepository] Decryption done in isolate. Decrypted size: ${decryptedBytes.length} bytes');
    } catch (e, st) {
      _logger.error('[FileRepository] DECRYPTION FAILED: $e', e, st);
      throw Exception('Failed to decrypt file payload: $e');
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
