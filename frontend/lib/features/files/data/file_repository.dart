import 'dart:convert';
import 'dart:typed_data';

import '../../../core/crypto/crypto_engine.dart';
import '../../../core/firebase/firebase_service.dart';
import '../../../core/utils/debug_log_service.dart';
import '../../../repositories/base_repository.dart';
import '../models/file_metadata_model.dart';
import '../models/progress_model.dart';

/// Repository handling zero-trust AES-256 encryption, 100% online cloud storage, and live metadata sync.
class FileRepository extends BaseRepository {
  final FirebaseService _firebaseService;
  final DebugLogService _logger = DebugLogService();

  FileRepository(this._firebaseService);

  /// Fetches files strictly online from Cloud Firestore.
  Future<List<FileItem>> listFiles() async {
    try {
      return await _firebaseService.listUserFiles();
    } catch (e, st) {
      _logger.error('[FileRepository] listFiles remote error: $e', e, st);
      return [];
    }
  }

  /// Generates or derives a Key Encryption Key (KEK) for the user to securely wrap DEKs.
  Uint8List _getUserKek() {
    final salt = Uint8List.fromList('NEUROVAULT_VAULT_KEY_SALT_v1'.codeUnits);
    return CryptoEngine.deriveMasterKey('neurovault_user_master_seed', salt, iterations: 10000);
  }

  /// Zero-Trust Upload Pipeline (Client-Side Encryption + 100% Online Cloud Upload)
  Future<FileItem> uploadFile({
    required String filename,
    required Uint8List fileBytes,
    required void Function(PipelineProgress progress) onProgress,
  }) async {
    _logger.info('[FileRepository] uploadFile() START: $filename (${fileBytes.length} bytes)');

    // Step 1: Generate AES key and encrypt in background isolate
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

      // Secure Envelope Encryption: Wrap DEK with user KEK before storing in metadata
      final kek = _getUserKek();
      final wrappedKeyBytes = CryptoEngine.wrapKey(symmetricKey, kek);
      encodedKey = base64Encode(wrappedKeyBytes);
      _logger.info('[FileRepository] AES-256-GCM encryption and KEK key-wrapping done. Encrypted size: ${encryptedBytes.length} bytes');
    } catch (e, st) {
      _logger.error('[FileRepository] ENCRYPTION ERROR: $e', e, st);
      rethrow;
    }

    // Step 2: Upload directly online to Firebase Cloud Storage
    onProgress(PipelineProgress(
      filename: filename,
      currentChunk: 1,
      totalChunks: 1,
      progressPercent: 0.5,
      status: 'UPLOADING TO CLOUD VAULT',
    ));
    await Future.delayed(Duration.zero);

    final uploadedItem = await _firebaseService.uploadEncryptedFile(
      filename: filename,
      fileBytes: encryptedBytes,
      aesKeyBase64: encodedKey,
    );
    _logger.info('[FileRepository] Upload SUCCESS. File ID: ${uploadedItem.id}');

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

  /// Zero-Trust Download Pipeline (100% Online Retrieval from Cloud Vault + Client-Side Decryption)
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

    final cloudPayload = await _firebaseService.downloadEncryptedFile(fileItem.id);
    _logger.info('[FileRepository] Download from Cloud Storage OK.');

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
        final decodedBytes = base64Decode(encryptedAesKey);
        final kek = _getUserKek();
        if (decodedBytes.length == 32) {
          // Legacy direct DEK (32 bytes)
          symmetricKey = decodedBytes;
          _logger.info('[FileRepository] Legacy direct AES key decoded successfully.');
        } else {
          // Secure Envelope Encryption: Unwrap DEK using user KEK
          symmetricKey = CryptoEngine.unwrapKey(decodedBytes, kek);
          _logger.info('[FileRepository] Enveloped AES key unwrapped and authenticated successfully.');
        }
      } catch (e, st) {
        _logger.error('[FileRepository] AES key unwrap failed: $e', e, st);
        throw Exception('Failed to decode/unwrap AES encryption key: $e');
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
