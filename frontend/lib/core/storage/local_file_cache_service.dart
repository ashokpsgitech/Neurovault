import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:path_provider/path_provider.dart';

import '../../features/files/models/file_metadata_model.dart';
import '../firebase/firebase_service.dart';
import '../utils/debug_log_service.dart';

/// Local persistent cache service for Vault files metadata and payloads.
/// Strictly scoped per authenticated User Account ID to prevent cross-account file data leakage on shared devices.
class LocalFileCacheService {
  static final LocalFileCacheService _instance = LocalFileCacheService._internal();
  factory LocalFileCacheService() => _instance;
  LocalFileCacheService._internal();

  String _getUserId([String? explicitUserId]) {
    if (explicitUserId != null && explicitUserId.isNotEmpty) {
      return explicitUserId;
    }
    final user = FirebaseService().currentUser;
    if (user != null && user.uid.isNotEmpty) {
      return user.uid;
    }
    return 'guest';
  }

  Future<File> _getFile([String? explicitUserId]) async {
    final userId = _getUserId(explicitUserId);
    final dir = await getApplicationDocumentsDirectory();
    return File('${dir.path}/vault_files_cache_$userId.json');
  }

  /// Loads cached files from local disk storage for the current authenticated user.
  Future<List<FileItem>> loadCachedFiles([String? explicitUserId]) async {
    final userId = _getUserId(explicitUserId);
    try {
      final file = await _getFile(userId);
      if (!await file.exists()) return [];

      final content = await file.readAsString();
      if (content.trim().isEmpty) return [];

      final List<dynamic> jsonList = jsonDecode(content);
      final files = jsonList.map((i) => FileItem.fromJson(i as Map<String, dynamic>)).toList();
      DebugLogService().info('[LocalFileCache] Loaded ${files.length} persistent files for user ($userId).');
      return files;
    } catch (e, st) {
      DebugLogService().error('[LocalFileCache] loadCachedFiles error for user ($userId): $e', e, st);
      return [];
    }
  }

  /// Persists a single uploaded file metadata item to disk for the current user.
  Future<void> saveFile(FileItem item, [String? explicitUserId]) async {
    final userId = _getUserId(explicitUserId);
    try {
      final current = await loadCachedFiles(userId);
      current.removeWhere((f) => f.id == item.id);
      current.insert(0, item);
      await saveAllFiles(current, userId);
      DebugLogService().info('[LocalFileCache] Saved file: ${item.filename} (id: ${item.id}) for user ($userId)');
    } catch (e, st) {
      DebugLogService().error('[LocalFileCache] saveFile error for user ($userId): $e', e, st);
    }
  }

  /// Persists a list of files to disk for the current user.
  Future<void> saveAllFiles(List<FileItem> items, [String? explicitUserId]) async {
    final userId = _getUserId(explicitUserId);
    try {
      final file = await _getFile(userId);
      final jsonList = items.map((f) => f.toJson()).toList();
      await file.writeAsString(jsonEncode(jsonList), flush: true);
    } catch (e, st) {
      DebugLogService().error('[LocalFileCache] saveAllFiles error for user ($userId): $e', e, st);
    }
  }

  /// Removes a file metadata item from persistent disk cache for the current user.
  Future<void> removeFile(String fileId, [String? explicitUserId]) async {
    final userId = _getUserId(explicitUserId);
    try {
      final current = await loadCachedFiles(userId);
      current.removeWhere((f) => f.id == fileId);
      await saveAllFiles(current, userId);
    } catch (e, st) {
      DebugLogService().error('[LocalFileCache] removeFile error for user ($userId): $e', e, st);
    }
  }

  /// Merges remote files with local persistent files (deduplicating by file ID or filename) for the current user.
  Future<List<FileItem>> mergeWithRemote(List<FileItem> remoteFiles, [String? explicitUserId]) async {
    final userId = _getUserId(explicitUserId);
    final cached = await loadCachedFiles(userId);
    final Map<String, FileItem> map = {};

    // First add cached files for this user
    for (final f in cached) {
      map[f.id] = f;
      map[f.filename] = f;
    }

    // Then overwrite/add with remote files for this user
    for (final f in remoteFiles) {
      map[f.id] = f;
      map[f.filename] = f;
    }

    final merged = map.values.toSet().toList();
    merged.sort((a, b) => b.createdAt.compareTo(a.createdAt));

    // Save consolidated list back to this user's cache
    await saveAllFiles(merged, userId);
    return merged;
  }

  /// Saves encrypted payload bytes and base64 key to user-scoped local storage directory.
  Future<void> saveEncryptedPayload(String fileId, Uint8List encryptedBytes, String aesKeyBase64, [String? explicitUserId]) async {
    final userId = _getUserId(explicitUserId);
    try {
      final dir = await getApplicationDocumentsDirectory();
      final payloadDir = Directory('${dir.path}/vault_payloads_$userId');
      if (!await payloadDir.exists()) {
        await payloadDir.create(recursive: true);
      }
      final payloadFile = File('${payloadDir.path}/$fileId.bin');
      await payloadFile.writeAsBytes(encryptedBytes, flush: true);

      final keyFile = File('${payloadDir.path}/$fileId.key');
      await keyFile.writeAsString(aesKeyBase64, flush: true);
      DebugLogService().info('[LocalFileCache] Saved local payload for fileId ($fileId) user ($userId)');
    } catch (e, st) {
      DebugLogService().error('[LocalFileCache] saveEncryptedPayload error for user ($userId): $e', e, st);
    }
  }

  /// Checks if encrypted payload exists locally on disk for current user.
  Future<bool> hasLocalPayload(String fileId, [String? explicitUserId]) async {
    final userId = _getUserId(explicitUserId);
    try {
      final dir = await getApplicationDocumentsDirectory();
      final payloadFile = File('${dir.path}/vault_payloads_$userId/$fileId.bin');
      final keyFile = File('${dir.path}/vault_payloads_$userId/$fileId.key');
      return await payloadFile.exists() && await keyFile.exists();
    } catch (_) {
      return false;
    }
  }

  /// Reads local encrypted payload bytes and base64 AES key for current user.
  Future<Map<String, dynamic>?> loadEncryptedPayload(String fileId, [String? explicitUserId]) async {
    final userId = _getUserId(explicitUserId);
    try {
      final dir = await getApplicationDocumentsDirectory();
      final payloadFile = File('${dir.path}/vault_payloads_$userId/$fileId.bin');
      final keyFile = File('${dir.path}/vault_payloads_$userId/$fileId.key');

      if (await payloadFile.exists() && await keyFile.exists()) {
        final bytes = await payloadFile.readAsBytes();
        final key = await keyFile.readAsString();
        return {
          'encryptedBytes': bytes,
          'encryptedAesKey': key,
        };
      }
    } catch (e, st) {
      DebugLogService().error('[LocalFileCache] loadEncryptedPayload error for user ($userId): $e', e, st);
    }
    return null;
  }
}
