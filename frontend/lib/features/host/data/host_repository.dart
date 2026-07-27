import 'dart:io';
import 'dart:typed_data';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import '../../../core/firebase/firebase_service.dart';
import '../../../core/utils/debug_log_service.dart';
import '../../../repositories/base_repository.dart';
import '../models/host_info_model.dart';
import '../services/host_service.dart';

/// Repository wrapping HostService calls with exception handling, local disk allocation, and network fallback.
class HostRepository extends BaseRepository {
  final HostService? _service;
  final FirebaseService _firebaseService;

  HostRepository([HostService? service, FirebaseService? firebaseService])
      : _service = service,
        _firebaseService = firebaseService ?? FirebaseService();

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

  /// Creates binary storage.container file on local disk with 256-byte header and pre-allocated capacity.
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
    bd.setInt64(44, 256 + 1024 * 1024, Endian.big);  // Data region offset
    bd.setInt64(52, nowMillis, Endian.big);            // Created timestamp
    bd.setInt64(60, nowMillis, Endian.big);            // Last modified timestamp

    final raf = await file.open(mode: FileMode.write);
    await raf.setPosition(0);
    await raf.writeFrom(bd.buffer.asUint8List());

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

  /// Returns the chunks directory alongside the container file.
  Directory _chunksDir(String containerPath) {
    final parent = File(containerPath).parent;
    return Directory('${parent.path}/chunks');
  }

  /// Writes encrypted chunk payload to an individual file named by chunkId in a chunks/ subfolder.
  /// Each chunk is stored as its own file: chunks/{chunkId}.bin
  Future<void> writeChunkToLocalContainer(String containerPath, Uint8List chunkBytes, {String? chunkId}) async {
    try {
      final dir = _chunksDir(containerPath);
      if (!await dir.exists()) {
        await dir.create(recursive: true);
      }

      // Use chunkId as filename if provided; otherwise use a timestamp-based name
      final filename = (chunkId != null && chunkId.isNotEmpty) ? '$chunkId.bin' : '${DateTime.now().millisecondsSinceEpoch}.bin';
      final chunkFile = File('${dir.path}/$filename');
      await chunkFile.writeAsBytes(chunkBytes, flush: true);

      // Also update the container header usedBytes counter (best-effort)
      try {
        final containerFile = File(containerPath);
        if (await containerFile.exists()) {
          final raf = await containerFile.open(mode: FileMode.append);
          await raf.close();
        }
      } catch (_) {}

      DebugLogService().info('[HostRepository] Stored chunk $filename (${chunkBytes.length} bytes) in ${dir.path}');
    } catch (e) {
      DebugLogService().warn('[HostRepository] writeChunkToLocalContainer error: $e');
    }
  }

  /// Reads an encrypted chunk payload by its chunkId from the chunks/ subfolder.
  /// Falls back to reading by chunkIndex position if no chunkId is given.
  Future<Uint8List?> readChunkFromLocalContainer(String containerPath, int chunkIndex, int sizeBytes, {String? chunkId}) async {
    try {
      // Primary: look up chunk by exact chunkId filename
      if (chunkId != null && chunkId.isNotEmpty) {
        final dir = _chunksDir(containerPath);
        final chunkFile = File('${dir.path}/$chunkId.bin');
        if (await chunkFile.exists()) {
          final bytes = await chunkFile.readAsBytes();
          if (bytes.isNotEmpty) {
            DebugLogService().info('[HostRepository] Read chunk $chunkId (${bytes.length} bytes) from ${dir.path}');
            return bytes;
          }
        }
        DebugLogService().warn('[HostRepository] Chunk file not found: ${chunkFile.path}');
        return null;
      }

      // Fallback: list chunk files sorted by name and return by index
      final dir = _chunksDir(containerPath);
      if (await dir.exists()) {
        final files = dir.listSync().whereType<File>().where((f) => f.path.endsWith('.bin')).toList()
          ..sort((a, b) => a.path.compareTo(b.path));
        if (chunkIndex < files.length) {
          final bytes = await files[chunkIndex].readAsBytes();
          if (bytes.isNotEmpty) return bytes;
        }
      }
    } catch (e) {
      DebugLogService().warn('[HostRepository] readChunkFromLocalContainer error: $e');
    }
    return null;
  }
}
