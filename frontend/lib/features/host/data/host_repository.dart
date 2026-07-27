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
      final hostId = 'local-node-${DateTime.now().millisecondsSinceEpoch}';
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

  /// Appends encrypted chunk payload to storage.container file at the data region offset.
  Future<void> writeChunkToLocalContainer(String containerPath, Uint8List chunkBytes) async {
    try {
      final file = File(containerPath);
      if (!await file.exists()) return;

      final raf = await file.open(mode: FileMode.append);

      // Read header at offset 0
      await raf.setPosition(0);
      final headerBytes = await raf.read(256);
      if (headerBytes.length >= 64 &&
          headerBytes[0] == 0x4E &&
          headerBytes[1] == 0x56 &&
          headerBytes[2] == 0x4C &&
          headerBytes[3] == 0x54) {
        final bd = ByteData.sublistView(Uint8List.fromList(headerBytes));
        final currentUsed = bd.getInt64(16, Endian.big);
        final currentChunks = bd.getInt32(24, Endian.big);
        final dataOffset = bd.getInt64(44, Endian.big);

        final writePos = dataOffset + currentUsed;
        await raf.setPosition(writePos);
        await raf.writeFrom(chunkBytes);

        // Update header used size and chunk count
        bd.setInt64(16, currentUsed + chunkBytes.length, Endian.big);
        bd.setInt32(24, currentChunks + 1, Endian.big);
        bd.setInt64(60, DateTime.now().millisecondsSinceEpoch, Endian.big);

        await raf.setPosition(0);
        await raf.writeFrom(bd.buffer.asUint8List());
        DebugLogService().info('[HostRepository] Appended ${chunkBytes.length} bytes to storage container at offset $writePos.');
      }
      await raf.close();
    } catch (e) {
      DebugLogService().warn('[HostRepository] writeChunkToLocalContainer error: $e');
    }
  }

  /// Reads encrypted chunk payload bytes from storage.container file on local disk.
  Future<Uint8List?> readChunkFromLocalContainer(String containerPath, int chunkIndex, int sizeBytes) async {
    try {
      final file = File(containerPath);
      if (!await file.exists()) return null;

      final raf = await file.open(mode: FileMode.read);
      await raf.setPosition(0);
      final headerBytes = await raf.read(256);
      if (headerBytes.length >= 64 &&
          headerBytes[0] == 0x4E &&
          headerBytes[1] == 0x56 &&
          headerBytes[2] == 0x4C &&
          headerBytes[3] == 0x54) {
        final bd = ByteData.sublistView(Uint8List.fromList(headerBytes));
        final dataOffset = bd.getInt64(44, Endian.big);

        await raf.setPosition(dataOffset);
        final fileLen = await file.length();
        final bytesToRead = (sizeBytes > 0 && (dataOffset + sizeBytes) <= fileLen)
            ? sizeBytes
            : (fileLen - dataOffset);

        if (bytesToRead > 0) {
          final bytes = await raf.read(bytesToRead);
          await raf.close();
          if (bytes.isNotEmpty) {
            return Uint8List.fromList(bytes);
          }
        }
      }
      await raf.close();
    } catch (e) {
      DebugLogService().warn('[HostRepository] readChunkFromLocalContainer error: $e');
    }
    return null;
  }
}
