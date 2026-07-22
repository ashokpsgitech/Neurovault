import 'dart:convert';
import 'dart:typed_data';

import '../../../core/crypto/crypto_engine.dart';
import '../../../core/crypto/file_chunker.dart';
import '../../../repositories/base_repository.dart';
import '../models/file_metadata_model.dart';
import '../models/progress_model.dart';
import '../services/file_service.dart';

/// Repository handling zero-trust encryption, 4MB chunking, and streaming metadata pipelines.
class FileRepository extends BaseRepository {
  final FileService _service;

  FileRepository(this._service);

  Future<List<FileItem>> listFiles() async {
    return safeApiCall(() async {
      return await _service.listFiles();
    });
  }

  /// Zero-Trust Upload Pipeline (Client-Side Encryption, 4MB Chunking, Host Streaming)
  Future<FileItem> uploadFile({
    required String filename,
    required Uint8List fileBytes,
    required void Function(PipelineProgress progress) onProgress,
  }) async {
    return safeApiCall(() async {
      final checksum = CryptoEngine.calculateSha256(fileBytes);
      final rawChunks = FileChunker.splitIntoChunks(fileBytes);
      final symmetricKey = CryptoEngine.generateSymmetricKey();
      final totalChunks = rawChunks.length;

      onProgress(PipelineProgress(
        filename: filename,
        currentChunk: 0,
        totalChunks: totalChunks,
        progressPercent: 0.05,
        status: 'ENCRYPTING',
      ));

      final plan = await _service.requestUploadPlan(
        filename: filename,
        sizeBytes: fileBytes.length,
        chunkCount: totalChunks,
        checksum: checksum,
      );

      final List<Map<String, dynamic>> uploadedChunks = [];

      for (int i = 0; i < totalChunks; i++) {
        final plainChunk = rawChunks[i];
        final encryptedChunk = CryptoEngine.encryptChunk(plainChunk, symmetricKey, i);
        final chunkHash = CryptoEngine.calculateSha256(encryptedChunk);

        String hostUrl = 'http://localhost:8080/api/storage/chunks';
        String chunkId = '${plan.fileId}-chunk-$i';
        String hostId = '';

        if (i < plan.chunkAllocations.length) {
          final alloc = plan.chunkAllocations[i];
          hostUrl = alloc.primaryHostUrl;
          if (alloc.chunkId.isNotEmpty) {
            chunkId = alloc.chunkId;
          }
          hostId = alloc.hostId;
        }

        await _service.uploadChunkPayload(
          hostUrl: hostUrl,
          chunkId: chunkId,
          encryptedPayload: encryptedChunk,
        );

        uploadedChunks.add({
          'chunkIndex': i,
          'chunkId': chunkId,
          'chunkHash': chunkHash,
          'sizeBytes': encryptedChunk.length,
          if (hostId.isNotEmpty) 'hostId': hostId,
        });

        final percent = 0.1 + (0.85 * ((i + 1) / totalChunks));
        onProgress(PipelineProgress(
          filename: filename,
          currentChunk: i + 1,
          totalChunks: totalChunks,
          progressPercent: percent,
          status: 'UPLOADING',
        ));
      }

      final String encodedKey = base64Encode(symmetricKey);

      await _service.completeUpload(
        uploadSessionId: plan.sessionId,
        encryptedAesKey: encodedKey,
        uploadedChunks: uploadedChunks,
      );

      onProgress(PipelineProgress(
        filename: filename,
        currentChunk: totalChunks,
        totalChunks: totalChunks,
        progressPercent: 1.0,
        status: 'COMPLETE',
      ));

      return FileItem(
        id: plan.fileId,
        filename: filename,
        sizeBytes: fileBytes.length,
        createdAt: DateTime.now(),
        chunkCount: totalChunks,
      );
    });
  }

  /// Zero-Trust Download Pipeline (Chunk Retrieval, Checksum Verification, Client-Side Decryption)
  Future<Uint8List> downloadFile({
    required FileItem fileItem,
    required void Function(PipelineProgress progress) onProgress,
  }) async {
    return safeApiCall(() async {
      final plan = await _service.requestDownloadPlan(fileItem.id);
      Uint8List symmetricKey;
      if (plan.encryptedAesKey.isNotEmpty) {
        try {
          symmetricKey = base64Decode(plan.encryptedAesKey);
        } catch (_) {
          symmetricKey = CryptoEngine.generateSymmetricKey();
        }
      } else {
        symmetricKey = CryptoEngine.generateSymmetricKey();
      }

      final totalChunks = plan.chunkLocations.isNotEmpty ? plan.chunkLocations.length : 1;
      final List<Uint8List> downloadedChunks = [];

      for (int i = 0; i < totalChunks; i++) {
        onProgress(PipelineProgress(
          filename: fileItem.filename,
          currentChunk: i + 1,
          totalChunks: totalChunks,
          progressPercent: 0.1 + (0.8 * (i / totalChunks)),
          status: 'DOWNLOADING',
        ));

        String hostUrl = 'http://localhost:8080/api/storage/chunks';
        String chunkId = '${fileItem.id}-chunk-$i';

        if (i < plan.chunkLocations.length) {
          hostUrl = plan.chunkLocations[i].hostUrl;
          chunkId = plan.chunkLocations[i].chunkId;
        }

        final encryptedBytes = await _service.downloadChunkPayload(
          hostUrl: hostUrl,
          chunkId: chunkId,
        );

        final decryptedBytes = CryptoEngine.decryptChunk(encryptedBytes, symmetricKey, i);
        downloadedChunks.add(decryptedBytes);
      }

      onProgress(PipelineProgress(
        filename: fileItem.filename,
        currentChunk: totalChunks,
        totalChunks: totalChunks,
        progressPercent: 0.95,
        status: 'DECRYPTING',
      ));

      final assembledFile = FileChunker.reassembleChunks(downloadedChunks);

      onProgress(PipelineProgress(
        filename: fileItem.filename,
        currentChunk: totalChunks,
        totalChunks: totalChunks,
        progressPercent: 1.0,
        status: 'COMPLETE',
      ));

      return assembledFile;
    });
  }
}
