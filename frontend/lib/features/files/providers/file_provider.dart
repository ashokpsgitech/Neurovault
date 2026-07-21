import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/errors/failures.dart';
import '../../../providers/core_providers.dart';
import '../data/file_repository.dart';
import '../models/file_metadata_model.dart';
import '../services/file_service.dart';
import 'file_state.dart';

final fileServiceProvider = Provider<FileService>((ref) {
  final dioClient = ref.watch(dioClientProvider);
  return FileService(dioClient);
});

final fileRepositoryProvider = Provider<FileRepository>((ref) {
  final service = ref.watch(fileServiceProvider);
  return FileRepository(service);
});

final fileProvider = StateNotifierProvider<FileNotifier, FileState>((ref) {
  final repo = ref.watch(fileRepositoryProvider);
  return FileNotifier(repo);
});

/// Riverpod StateNotifier managing Vault files, streaming upload, and download pipelines.
class FileNotifier extends StateNotifier<FileState> {
  final FileRepository _repository;
  final List<FileItem> _inMemoryFiles = [];

  FileNotifier(this._repository) : super(const FileInitial()) {
    loadFiles();
  }

  Future<void> loadFiles() async {
    state = const FileLoading();
    try {
      final remoteFiles = await _repository.listFiles();
      for (final item in remoteFiles) {
        if (!_inMemoryFiles.any((f) => f.id == item.id)) {
          _inMemoryFiles.add(item);
        }
      }
      state = FileLoaded(List.unmodifiable(_inMemoryFiles));
    } catch (_) {
      state = FileLoaded(List.unmodifiable(_inMemoryFiles));
    }
  }

  Future<void> uploadFile({
    required String filename,
    required Uint8List fileBytes,
  }) async {
    try {
      final uploadedItem = await _repository.uploadFile(
        filename: filename,
        fileBytes: fileBytes,
        onProgress: (progress) {
          state = FileLoaded(List.unmodifiable(_inMemoryFiles), progress);
        },
      );

      _inMemoryFiles.insert(0, uploadedItem);
      state = FileLoaded(List.unmodifiable(_inMemoryFiles));
    } on Failure catch (f) {
      state = FileError(f.message);
    } catch (e) {
      state = FileError(e.toString());
    }
  }

  Future<Uint8List?> downloadFile(FileItem fileItem) async {
    try {
      final bytes = await _repository.downloadFile(
        fileItem: fileItem,
        onProgress: (progress) {
          state = FileLoaded(List.unmodifiable(_inMemoryFiles), progress);
        },
      );
      state = FileLoaded(List.unmodifiable(_inMemoryFiles));
      return bytes;
    } on Failure catch (f) {
      state = FileError(f.message);
      return null;
    } catch (e) {
      state = FileError(e.toString());
      return null;
    }
  }
}
