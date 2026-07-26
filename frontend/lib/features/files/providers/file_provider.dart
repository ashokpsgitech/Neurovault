import 'dart:developer' as dev;
import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/firebase/firebase_service.dart';
import '../../../core/utils/debug_log_service.dart';
import '../data/file_repository.dart';
import '../models/file_metadata_model.dart';
import 'file_state.dart';

final fileRepositoryProvider = Provider<FileRepository>((ref) {
  final firebaseService = FirebaseService();
  return FileRepository(firebaseService);
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
    dev.log('[FileNotifier] loadFiles() called');
    state = const FileLoading();
    try {
      final remoteFiles = await _repository.listFiles();
      _inMemoryFiles.clear();
      _inMemoryFiles.addAll(remoteFiles);
      dev.log('[FileNotifier] loadFiles() loaded ${remoteFiles.length} files');
      state = FileLoaded(List.unmodifiable(_inMemoryFiles));
    } catch (e, st) {
      dev.log('[FileNotifier] loadFiles() error: $e', error: e, stackTrace: st);
      DebugLogService().error('[FileNotifier] loadFiles failed: $e');
      state = FileError('Failed to load files: ${e.toString()}');
    }
  }

  /// Uploads file — throws on error so UploadDialog can display the exact error message.
  Future<void> uploadFile({
    required String filename,
    required Uint8List fileBytes,
  }) async {
    dev.log('[FileNotifier] uploadFile() called: $filename (${fileBytes.length} bytes)');
    try {
      final uploadedItem = await _repository.uploadFile(
        filename: filename,
        fileBytes: fileBytes,
        onProgress: (progress) {
          dev.log('[FileNotifier] Progress: ${(progress.progressPercent * 100).toStringAsFixed(0)}% — ${progress.status}');
          state = FileLoaded(List.unmodifiable(_inMemoryFiles), progress);
        },
      );

      _inMemoryFiles.removeWhere((f) => f.id == uploadedItem.id);
      _inMemoryFiles.insert(0, uploadedItem);
      state = FileLoaded(List.unmodifiable(_inMemoryFiles));
      dev.log('[FileNotifier] uploadFile() DONE. File id: ${uploadedItem.id}');
    } catch (e, st) {
      dev.log('[FileNotifier] uploadFile() FAILED: $e', error: e, stackTrace: st);
      // Restore files list (don't leave in error state)
      state = FileLoaded(List.unmodifiable(_inMemoryFiles));
      // Rethrow so UploadDialog shows the actual error
      rethrow;
    }
  }

  Future<Uint8List?> downloadFile(FileItem fileItem) async {
    dev.log('[FileNotifier] downloadFile() called: ${fileItem.filename}');
    try {
      final bytes = await _repository.downloadFile(
        fileItem: fileItem,
        onProgress: (progress) {
          dev.log('[FileNotifier] Download Progress: ${(progress.progressPercent * 100).toStringAsFixed(0)}% — ${progress.status}');
          state = FileLoaded(List.unmodifiable(_inMemoryFiles), progress);
        },
      );
      state = FileLoaded(List.unmodifiable(_inMemoryFiles));
      dev.log('[FileNotifier] downloadFile() DONE. ${bytes.length} bytes');
      return bytes;
    } catch (e, st) {
      dev.log('[FileNotifier] downloadFile() FAILED: $e', error: e, stackTrace: st);
      state = FileLoaded(List.unmodifiable(_inMemoryFiles));
      rethrow;
    }
  }
}
