import 'dart:typed_data';
import 'package:dio/dio.dart';

import '../../../core/constants/api_constants.dart';
import '../../../core/network/dio_client.dart';
import '../models/file_metadata_model.dart';

/// Network service handling REST requests to Coordinator & Storage Host nodes.
class FileService {
  final DioClient _dioClient;

  FileService(this._dioClient);

  /// Requests upload plan metadata from Coordinator.
  Future<UploadPlanResponse> requestUploadPlan({
    required String filename,
    required int sizeBytes,
    required int chunkCount,
    required String checksum,
  }) async {
    final response = await _dioClient.dio.post(
      ApiConstants.uploadPlan,
      data: {
        'filename': filename,
        'sizeBytes': sizeBytes,
        'chunkCount': chunkCount,
        'sha256Checksum': checksum,
      },
    );
    return UploadPlanResponse.fromJson(response.data);
  }

  /// Directly streams encrypted chunk bytes to host node at /api/storage/chunks.
  Future<void> uploadChunkPayload({
    required String hostUrl,
    required String chunkId,
    required Uint8List encryptedPayload,
  }) async {
    final targetUrl = '$hostUrl/api/storage/chunks';
    final formData = FormData.fromMap({
      'chunkId': chunkId,
      'file': MultipartFile.fromBytes(encryptedPayload, filename: '$chunkId.bin'),
    });

    try {
      await _dioClient.dio.post(targetUrl, data: formData);
    } catch (_) {
      // Fallback to local storage endpoint if direct host URL fails
      await _dioClient.dio.post(ApiConstants.storeChunk, data: formData);
    }
  }

  /// Notifies Coordinator that upload has completed.
  Future<void> completeUpload(String sessionId) async {
    await _dioClient.dio.post(
      ApiConstants.uploadComplete,
      data: {
        'sessionId': sessionId,
        'successful': true,
      },
    );
  }

  /// Requests download plan from Coordinator.
  Future<DownloadPlanResponse> requestDownloadPlan(String fileId) async {
    final response = await _dioClient.dio.get('${ApiConstants.downloadPlan}$fileId');
    return DownloadPlanResponse.fromJson(response.data);
  }

  /// Downloads encrypted chunk bytes directly from host node.
  Future<Uint8List> downloadChunkPayload({
    required String hostUrl,
    required String chunkId,
  }) async {
    final targetUrl = '$hostUrl/api/storage/chunks/$chunkId';
    try {
      final response = await _dioClient.dio.get<List<int>>(
        targetUrl,
        options: Options(responseType: ResponseType.bytes),
      );
      return Uint8List.fromList(response.data ?? []);
    } catch (_) {
      final response = await _dioClient.dio.get<List<int>>(
        '${ApiConstants.readChunk}$chunkId',
        options: Options(responseType: ResponseType.bytes),
      );
      return Uint8List.fromList(response.data ?? []);
    }
  }

  /// Fetches list of files uploaded by the user.
  Future<List<FileItem>> listFiles() async {
    try {
      final response = await _dioClient.dio.get('/api/files');
      final list = response.data as List? ?? [];
      return list.map((i) => FileItem.fromJson(i)).toList();
    } catch (_) {
      return [];
    }
  }
}
