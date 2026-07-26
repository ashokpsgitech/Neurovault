import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:path_provider/path_provider.dart';

import '../../features/files/models/file_metadata_model.dart';
import '../utils/debug_log_service.dart';

/// Local persistent cache service for Vault files metadata.
/// Ensures uploaded files persist across app restarts, reloads, and screen navigations.
class LocalFileCacheService {
  static final LocalFileCacheService _instance = LocalFileCacheService._internal();
  factory LocalFileCacheService() => _instance;
  LocalFileCacheService._internal();

  File? _cacheFile;

  Future<File> _getFile() async {
    if (_cacheFile != null) return _cacheFile!;
    final dir = await getApplicationDocumentsDirectory();
    _cacheFile = File('${dir.path}/vault_files_cache.json');
    return _cacheFile!;
  }

  /// Loads cached files from local disk storage.
  Future<List<FileItem>> loadCachedFiles() async {
    try {
      final file = await _getFile();
      if (!await file.exists()) return [];

      final content = await file.readAsString();
      if (content.trim().isEmpty) return [];

      final List<dynamic> jsonList = jsonDecode(content);
      final files = jsonList.map((i) => FileItem.fromJson(i as Map<String, dynamic>)).toList();
      DebugLogService().info('[LocalFileCache] Loaded ${files.length} persistent files from disk.');
      return files;
    } catch (e, st) {
      DebugLogService().error('[LocalFileCache] loadCachedFiles error: $e', e, st);
      return [];
    }
  }

  /// Persists a single uploaded file metadata item to disk.
  Future<void> saveFile(FileItem item) async {
    try {
      final current = await loadCachedFiles();
      current.removeWhere((f) => f.id == item.id);
      current.insert(0, item);
      await saveAllFiles(current);
      DebugLogService().info('[LocalFileCache] Saved file: ${item.filename} (id: ${item.id})');
    } catch (e, st) {
      DebugLogService().error('[LocalFileCache] saveFile error: $e', e, st);
    }
  }

  /// Persists a list of files to disk.
  Future<void> saveAllFiles(List<FileItem> items) async {
    try {
      final file = await _getFile();
      final jsonList = items.map((f) => f.toJson()).toList();
      await file.writeAsString(jsonEncode(jsonList), flush: true);
    } catch (e, st) {
      DebugLogService().error('[LocalFileCache] saveAllFiles error: $e', e, st);
    }
  }

  /// Removes a file metadata item from persistent disk cache.
  Future<void> removeFile(String fileId) async {
    try {
      final current = await loadCachedFiles();
      current.removeWhere((f) => f.id == fileId);
      await saveAllFiles(current);
    } catch (e, st) {
      DebugLogService().error('[LocalFileCache] removeFile error: $e', e, st);
    }
  }

  /// Merges remote files with local persistent files (deduplicating by file ID or filename).
  Future<List<FileItem>> mergeWithRemote(List<FileItem> remoteFiles) async {
    final cached = await loadCachedFiles();
    final Map<String, FileItem> map = {};

    // First add cached files
    for (final f in cached) {
      map[f.id] = f;
      map[f.filename] = f; // Map by filename too for deduplication
    }

    // Then overwrite/add with remote files
    for (final f in remoteFiles) {
      map[f.id] = f;
      map[f.filename] = f;
    }

    final merged = map.values.toSet().toList();
    merged.sort((a, b) => b.createdAt.compareTo(a.createdAt));

    // Save consolidated list back to cache
    await saveAllFiles(merged);
    return merged;
  }

  /// Saves encrypted payload bytes and base64 key to local storage directory.
  Future<void> saveEncryptedPayload(String fileId, Uint8List encryptedBytes, String aesKeyBase64) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final payloadDir = Directory('${dir.path}/vault_payloads');
      if (!await payloadDir.exists()) {
        await payloadDir.create(recursive: true);
      }
      final payloadFile = File('${payloadDir.path}/$fileId.bin');
      await payloadFile.writeAsBytes(encryptedBytes, flush: true);

      final keyFile = File('${payloadDir.path}/$fileId.key');
      await keyFile.writeAsString(aesKeyBase64, flush: true);
      DebugLogService().info('[LocalFileCache] Saved local encrypted payload for fileId: $fileId');
    } catch (e, st) {
      DebugLogService().error('[LocalFileCache] saveEncryptedPayload error: $e', e, st);
    }
  }

  /// Checks if encrypted payload exists locally on disk.
  Future<bool> hasLocalPayload(String fileId) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final payloadFile = File('${dir.path}/vault_payloads/$fileId.bin');
      final keyFile = File('${dir.path}/vault_payloads/$fileId.key');
      return await payloadFile.exists() && await keyFile.exists();
    } catch (_) {
      return false;
    }
  }

  /// Reads local encrypted payload bytes and base64 AES key.
  Future<Map<String, dynamic>?> loadEncryptedPayload(String fileId) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final payloadFile = File('${dir.path}/vault_payloads/$fileId.bin');
      final keyFile = File('${dir.path}/vault_payloads/$fileId.key');

      if (await payloadFile.exists() && await keyFile.exists()) {
        final bytes = await payloadFile.readAsBytes();
        final key = await keyFile.readAsString();
        return {
          'encryptedBytes': bytes,
          'encryptedAesKey': key,
        };
      }
    } catch (e, st) {
      DebugLogService().error('[LocalFileCache] loadEncryptedPayload error: $e', e, st);
    }
    return null;
  }
}
