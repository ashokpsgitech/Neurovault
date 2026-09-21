import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import '../../../core/crypto/file_chunker.dart';
import '../../../core/firebase/firebase_service.dart';
import '../../../core/utils/debug_log_service.dart';
import '../../../repositories/base_repository.dart';
import '../models/host_info_model.dart';
import '../services/host_service.dart';

/// Repository wrapping HostService calls with exception handling, local disk allocation, and network fallback.
class HostRepository extends BaseRepository {
  final HostService? _service;
  final FirebaseService? _customFirebaseService;
  FirebaseService get _firebaseService => _customFirebaseService ?? FirebaseService();

  HostRepository([HostService? service, FirebaseService? firebaseService])
      : _service = service,
        _customFirebaseService = firebaseService;

  Future<HostInfoModel> registerHost({
    required String name,
    required String deviceType,
    required String operatingSystem,
    required String publicIp,
    required int totalCapacityBytes,
    required int reservedCapacityBytes,
  }) async {
    HostInfoModel? hostInfo;
    if (_service != null) {
      try {
        hostInfo = await safeApiCall(() async {
          return await _service!.registerHost(
            name: name,
            deviceType: deviceType,
            operatingSystem: operatingSystem,
            publicIp: publicIp,
            totalCapacityBytes: totalCapacityBytes,
            reservedCapacityBytes: reservedCapacityBytes,
          );
        });
      } catch (e) {
        DebugLogService().warn('[HostRepository] Coordinator registration failed ($e). Operating in offline local fallback mode.');
      }
    }

    if (hostInfo == null) {
      final currentUser = await _firebaseService.getCurrentUser();
      final hostId = (currentUser != null && currentUser.id.isNotEmpty)
          ? 'host_${currentUser.id}'
          : 'host_local_node';
      final fallbackPath = await getDefaultContainerPath();
      hostInfo = HostInfoModel(
        id: hostId,
        name: name,
        deviceType: deviceType,
        operatingSystem: operatingSystem,
        publicIp: publicIp,
        totalCapacityBytes: totalCapacityBytes,
        reservedCapacityBytes: reservedCapacityBytes,
        usedCapacityBytes: 0,
        status: 'ONLINE',
        cpuUsagePercent: 0.0,
        ramUsagePercent: 0.0,
        containerPath: fallbackPath,
        containerCreated: true,
        activeChunks: 0,
        lastHeartbeat: DateTime.now(),
      );
    }

    // Always publish host status to Firebase Cloud Firestore for network-wide real-time tracking
    try {
      await _firebaseService.updateHostNodeStatus(
        hostId: hostInfo.id,
        hostname: name,
        status: 'ONLINE',
        reservedStorageBytes: reservedCapacityBytes,
      );
      DebugLogService().info('[HostRepository] Published ONLINE host status to Cloud Firestore for host: ${hostInfo.id}');
    } catch (e) {
      DebugLogService().error('[HostRepository] Failed to publish ONLINE host status to Cloud Firestore: $e');
    }

    return hostInfo;
  }

  Future<void> sendHeartbeat({
    required String hostId,
    required double cpuUsagePercent,
    required double ramUsagePercent,
    required int usedCapacityBytes,
    int? reservedStorageBytes,
  }) async {
    if (_service != null) {
      try {
        await _service!.sendHeartbeat(
          hostId: hostId,
          cpuUsagePercent: cpuUsagePercent,
          ramUsagePercent: ramUsagePercent,
          usedCapacityBytes: usedCapacityBytes,
        );
      } catch (e) {
        DebugLogService().warn('[HostRepository] Backend sendHeartbeat offline: $e');
      }
    }

    // Always refresh lastSeen pulse and capacity in Cloud Firestore
    try {
      await _firebaseService.updateHostNodeStatus(
        hostId: hostId,
        hostname: 'Host-$hostId',
        status: 'ONLINE',
        reservedStorageBytes: reservedStorageBytes ?? (10 * 1024 * 1024 * 1024),
        usedStorageBytes: usedCapacityBytes,
      );
    } catch (_) {}
  }

  Future<void> disableHostNode(String hostId, String hostname) async {
    try {
      await _firebaseService.updateHostNodeStatus(
        hostId: hostId,
        hostname: hostname,
        status: 'OFFLINE',
        reservedStorageBytes: 0,
      );
      DebugLogService().info('[HostRepository] Published OFFLINE host status to Cloud Firestore for host: $hostId');
    } catch (e) {
      DebugLogService().error('[HostRepository] disableHostNode error: $e');
    }
  }

  Future<HostInfoModel?> getHostStatus() async {
    if (_service != null) {
      try {
        return await _service!.getHostStatus();
      } catch (e) {
        DebugLogService().error('[HostRepository] getHostStatus failed: $e');
      }
    }
    return null;
  }

  /// Creates a pre-allocated binary disk container at the specified path with the chosen reservation size.
  Future<void> createStorageContainer(String hostId, int reservedGb, String containerPath) async {
    // 1. Request Android storage permissions (Android 6+)
    await _requestStoragePermissions();

    // 2. Resolve the actual container path (platform-aware)
    final resolvedPath = await _resolveContainerPath(containerPath);

    // 3. Create binary container file on disk (NVLT 256-byte header)
    await _createLocalContainerFileOnDisk(resolvedPath, reservedGb);

    // 4. Map reservation size enum for Spring Boot Coordinator backend
    String reservationEnum = 'GB_5';
    if (reservedGb <= 1) {
      reservationEnum = 'GB_1';
    } else if (reservedGb <= 2) {
      reservationEnum = 'GB_2';
    } else if (reservedGb <= 5) {
      reservationEnum = 'GB_5';
    } else if (reservedGb <= 10) {
      reservationEnum = 'GB_10';
    } else {
      reservationEnum = 'GB_20';
    }

    if (_service != null) {
      try {
        await _service!.createStorageContainer(
          hostId: hostId,
          containerPath: resolvedPath,
          reservationSize: reservationEnum,
        );
      } catch (e) {
        DebugLogService().warn('[HostRepository] Backend container registration offline: $e');
      }
    }
  }

  /// Request storage permissions on Android.
  Future<void> _requestStoragePermissions() async {
    if (!Platform.isAndroid) return;
    try {
      final status = await Permission.storage.request();
      if (!status.isGranted) {
        await Permission.manageExternalStorage.request();
      }
    } catch (e) {
      DebugLogService().error('[HostRepository] _requestStoragePermissions error: $e');
    }
  }

  /// Returns the platform-appropriate default container path.
  Future<String> getDefaultContainerPath() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      return '${dir.path}/storage.container';
    } catch (_) {}

    if (Platform.isWindows) {
      final userProfile = Platform.environment['USERPROFILE'] ?? 'C:';
      return '$userProfile\\NeuroVaultData\\storage.container';
    } else if (Platform.isMacOS || Platform.isLinux) {
      final home = Platform.environment['HOME'] ?? '.';
      return '$home/NeuroVaultData/storage.container';
    }
    return 'storage.container';
  }

  /// Resolves the container path to a valid writable location on the current platform.
  Future<String> _resolveContainerPath(String requestedPath) async {
    if (!Platform.isAndroid && !Platform.isIOS) {
      return requestedPath; // Desktop: use path as-is
    }

    // On Android: if path starts with Windows drive letter or is invalid, use app docs directory
    if (requestedPath.startsWith('D:\\') ||
        requestedPath.startsWith('C:\\') ||
        (!requestedPath.startsWith('/storage/emulated') &&
            !requestedPath.startsWith('/data/'))) {
      try {
        final dir = await getApplicationDocumentsDirectory();
        return '${dir.path}/storage.container';
      } catch (_) {}
    }

    // Validate the requested path is actually writable
    try {
      final dir = File(requestedPath).parent;
      if (!await dir.exists()) {
        await dir.create(recursive: true);
      }
      return requestedPath;
    } catch (_) {
      // Fall back to application documents directory
      try {
        final dir = await getApplicationDocumentsDirectory();
        return '${dir.path}/storage.container';
      } catch (_) {
        return requestedPath;
      }
    }
  }

  /// Creates binary storage.container file on local disk with 256-byte header, metadata region, and pre-allocated capacity.
  Future<void> _createLocalContainerFileOnDisk(String containerPath, int reservedGb) async {
    final file = File(containerPath);
    final parentDir = file.parent;

    // Create parent directories if needed
    if (!await parentDir.exists()) {
      await parentDir.create(recursive: true);
    }

    final totalBytes = reservedGb * 1024 * 1024 * 1024;
    final nowMillis = DateTime.now().millisecondsSinceEpoch;

    final bd = ByteData(256);
    // Magic Bytes "NVLT"
    bd.setUint8(0, 0x4E); // 'N'
    bd.setUint8(1, 0x56); // 'V'
    bd.setUint8(2, 0x4C); // 'L'
    bd.setUint8(3, 0x54); // 'T'

    bd.setInt32(4, 1, Endian.big);                    // Version 1
    bd.setInt64(8, totalBytes, Endian.big);           // Total size in bytes
    bd.setInt64(16, 0, Endian.big);                   // Used size (0)
    bd.setInt32(24, 0, Endian.big);                   // Chunk count (0)
    bd.setInt64(28, 256, Endian.big);                 // Metadata region offset
    bd.setInt64(36, 1024 * 1024, Endian.big);        // Metadata region size (1MB)
    bd.setInt64(44, 256 + 1024 * 1024, Endian.big);  // Data region offset (1048832)
    bd.setInt64(52, nowMillis, Endian.big);            // Created timestamp
    bd.setInt64(60, nowMillis, Endian.big);            // Last modified timestamp

    final raf = await file.open(mode: FileMode.write);
    await raf.setPosition(0);
    await raf.writeFrom(bd.buffer.asUint8List());

    // Initialize metadata index region (4 zero bytes = empty index)
    final idxZero = ByteData(4)..setInt32(0, 0, Endian.big);
    await raf.setPosition(256);
    await raf.writeFrom(idxZero.buffer.asUint8List());

    // Pre-allocate the full reserved container capacity on disk
    if (totalBytes > 256) {
      try {
        await raf.truncate(totalBytes);
        DebugLogService().info('[HostRepository] Successfully pre-allocated $reservedGb GB container file ($totalBytes bytes) on disk.');
      } catch (_) {
        try {
          await raf.setPosition(totalBytes - 1);
          await raf.writeByte(0);
          DebugLogService().info('[HostRepository] Pre-allocated container file ($totalBytes bytes) via tail byte.');
        } catch (e) {
          DebugLogService().warn('[HostRepository] Pre-allocation skipped: $e');
        }
      }
    }
    await raf.close();
  }

  /// Writes chunk bytes DIRECTLY at a byte offset INSIDE the single storage.container file.
  /// No files or folders are created outside the allocated container file.
  Future<void> writeChunkToLocalContainer(String containerPath, Uint8List chunkBytes, {String? chunkId}) async {
    final file = File(containerPath);
    if (!await file.exists()) {
      await _createLocalContainerFileOnDisk(containerPath, 10);
    }

    RandomAccessFile? raf;
    try {
      raf = await file.open(mode: FileMode.append);

      // 1. Read and validate 256-byte container header
      await raf.setPosition(0);
      final headerBytes = await raf.read(256);
      if (headerBytes.length < 256) {
        throw Exception('Invalid container file header (under 256 bytes)');
      }

      final bd = ByteData.sublistView(headerBytes);
      final isNvlt = headerBytes[0] == 0x4E && headerBytes[1] == 0x56 && headerBytes[2] == 0x4C && headerBytes[3] == 0x54;
      if (!isNvlt) {
        throw Exception('Invalid container magic bytes — expected NVLT');
      }

      final int totalSize = bd.getInt64(8, Endian.big);
      final int metadataOffset = bd.getInt64(28, Endian.big); // 256
      final int metadataSize = bd.getInt64(36, Endian.big);   // 1MB = 1048576
      final int dataRegionOffset = bd.getInt64(44, Endian.big); // 1048832

      // 2. Read Metadata Index from container metadata region (offset 256)
      await raf.setPosition(metadataOffset);
      final idxLenBytes = await raf.read(4);
      final int idxLen = (idxLenBytes.length == 4) ? ByteData.sublistView(idxLenBytes).getInt32(0, Endian.big) : 0;

      List<Map<String, dynamic>> chunkEntries = [];
      if (idxLen > 0 && idxLen < metadataSize - 4) {
        final idxPayload = await raf.read(idxLen);
        try {
          final decoded = jsonDecode(utf8.decode(idxPayload)) as List<dynamic>;
          chunkEntries = decoded.map((e) => Map<String, dynamic>.from(e as Map)).toList();
        } catch (_) {
          chunkEntries = [];
        }
      }

      // 3. Compute physical write offset inside Data Region
      int nextOffset = dataRegionOffset;
      for (final entry in chunkEntries) {
        if (entry['deleted'] != true) {
          final int entryEnd = (entry['offset'] as int? ?? 0) + (entry['length'] as int? ?? 0);
          if (entryEnd > nextOffset) {
            nextOffset = entryEnd;
          }
        }
      }

      if (nextOffset + chunkBytes.length > totalSize && totalSize > 0) {
        throw Exception('Container capacity exceeded. Need ${chunkBytes.length} bytes, at offset $nextOffset in $totalSize byte container');
      }

      // 4. Write chunk bytes physically INSIDE the container file
      await raf.setPosition(nextOffset);
      await raf.writeFrom(chunkBytes);

      // 5. Update Metadata Index entry
      final String effectiveChunkId = (chunkId != null && chunkId.isNotEmpty)
          ? chunkId
          : '${DateTime.now().millisecondsSinceEpoch}';

      chunkEntries.removeWhere((e) => e['chunkId'] == effectiveChunkId);
      chunkEntries.add({
        'chunkId': effectiveChunkId,
        'offset': nextOffset,
        'length': chunkBytes.length,
        'sha256': FileChunker.computeSha256(chunkBytes),
        'createdAt': DateTime.now().millisecondsSinceEpoch,
        'deleted': false,
      });

      // 6. Write updated Metadata Index inside container metadata region
      final indexJsonBytes = utf8.encode(jsonEncode(chunkEntries));
      if (indexJsonBytes.length > metadataSize - 4) {
        throw Exception('Container metadata index exceeded 1MB allocation');
      }

      final idxHeaderBd = ByteData(4)..setInt32(0, indexJsonBytes.length, Endian.big);
      await raf.setPosition(metadataOffset);
      await raf.writeFrom(idxHeaderBd.buffer.asUint8List());
      await raf.writeFrom(indexJsonBytes);

      // 7. Update container header (usedSize, chunkCount, lastModified)
      final int usedSize = chunkEntries
          .where((e) => e['deleted'] != true)
          .fold<int>(0, (sum, e) => sum + (e['length'] as int? ?? 0));
      final int activeCount = chunkEntries.where((e) => e['deleted'] != true).length;

      final updateBd = ByteData(12)
        ..setInt64(0, usedSize, Endian.big)
        ..setInt32(8, activeCount, Endian.big);

      await raf.setPosition(16);
      await raf.writeFrom(updateBd.buffer.asUint8List());

      final timeBd = ByteData(8)..setInt64(0, DateTime.now().millisecondsSinceEpoch, Endian.big);
      await raf.setPosition(60);
      await raf.writeFrom(timeBd.buffer.asUint8List());

      await raf.flush();
      DebugLogService().info('[HostRepository] Stored chunk $effectiveChunkId (${chunkBytes.length} bytes) directly INSIDE $containerPath at offset $nextOffset (total chunks in container: $activeCount, used: $usedSize bytes)');

    } catch (e) {
      DebugLogService().warn('[HostRepository] writeChunkToLocalContainer error: $e');
      rethrow;
    } finally {
      await raf?.close();
    }
  }

  /// Reads a chunk payload directly from its byte offset INSIDE the single storage.container file.
  Future<Uint8List?> readChunkFromLocalContainer(String containerPath, int chunkIndex, int sizeBytes, {String? chunkId}) async {
    final file = File(containerPath);
    if (!await file.exists()) {
      DebugLogService().warn('[HostRepository] Container file not found: $containerPath');
      return null;
    }

    RandomAccessFile? raf;
    try {
      raf = await file.open(mode: FileMode.read);

      // 1. Read header
      await raf.setPosition(0);
      final headerBytes = await raf.read(256);
      if (headerBytes.length < 256) return null;

      final bd = ByteData.sublistView(headerBytes);
      final isNvlt = headerBytes[0] == 0x4E && headerBytes[1] == 0x56 && headerBytes[2] == 0x4C && headerBytes[3] == 0x54;
      if (!isNvlt) return null;

      final int metadataOffset = bd.getInt64(28, Endian.big); // 256
      final int metadataSize = bd.getInt64(36, Endian.big);   // 1MB

      // 2. Read Metadata Index from offset 256
      await raf.setPosition(metadataOffset);
      final idxLenBytes = await raf.read(4);
      if (idxLenBytes.length < 4) return null;

      final int idxLen = ByteData.sublistView(idxLenBytes).getInt32(0, Endian.big);
      if (idxLen <= 0 || idxLen > metadataSize - 4) return null;

      final idxPayload = await raf.read(idxLen);
      final decoded = jsonDecode(utf8.decode(idxPayload)) as List<dynamic>;
      final chunkEntries = decoded.map((e) => Map<String, dynamic>.from(e as Map)).toList();

      // 3. Find matching chunk entry
      Map<String, dynamic>? matchEntry;
      if (chunkId != null && chunkId.isNotEmpty) {
        matchEntry = chunkEntries.cast<Map<String, dynamic>?>().firstWhere(
          (e) => e != null && e['chunkId'] == chunkId && e['deleted'] != true,
          orElse: () => null,
        );
      }

      // Fallback: match by active chunk index
      if (matchEntry == null) {
        final activeList = chunkEntries.where((e) => e['deleted'] != true).toList();
        if (chunkIndex >= 0 && chunkIndex < activeList.length) {
          matchEntry = activeList[chunkIndex];
        }
      }

      if (matchEntry == null) {
        DebugLogService().warn('[HostRepository] Chunk not found in container index: $chunkId (index $chunkIndex)');
        return null;
      }

      final int offset = matchEntry['offset'] as int? ?? 0;
      final int length = matchEntry['length'] as int? ?? 0;

      if (offset <= 0 || length <= 0) return null;

      // 4. Read physical bytes directly from inside the container at offset
      await raf.setPosition(offset);
      final chunkData = await raf.read(length);
      DebugLogService().info('[HostRepository] Read chunk ${matchEntry['chunkId']} ($length bytes) directly from INSIDE $containerPath at offset $offset');
      return chunkData;

    } catch (e) {
      DebugLogService().warn('[HostRepository] readChunkFromLocalContainer error: $e');
      return null;
    } finally {
      await raf?.close();
    }
  }
}
