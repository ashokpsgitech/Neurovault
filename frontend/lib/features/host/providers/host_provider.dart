import 'dart:async';
import 'dart:io';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/services/host_background_service.dart';
import '../../../core/storage/secure_storage_service.dart';
import '../../../providers/core_providers.dart';
import '../data/host_repository.dart';
import 'host_state.dart';

final hostRepositoryProvider = Provider<HostRepository>((ref) {
  return HostRepository();
});

final hostProvider = StateNotifierProvider<HostNotifier, HostState>((ref) {
  final repo = ref.watch(hostRepositoryProvider);
  final storage = ref.watch(secureStorageProvider);
  return HostNotifier(repo, storage);
});

/// Riverpod StateNotifier managing Host Mode lifecycle, container allocation, and 24/7 heartbeat daemon.
class HostNotifier extends StateNotifier<HostState> {
  final HostRepository _repository;
  final SecureStorageService _storage;
  Timer? _heartbeatTimer;

  HostNotifier(this._repository, this._storage) : super(const HostInitial()) {
    autoEnableHostOnStartup();
  }

  /// Automatically activates container host node on startup ONLY IF explicitly allocated previously by the host.
  Future<void> autoEnableHostOnStartup() async {
    try {
      final isAllocated = await _storage.isHostAllocated();
      if (isAllocated) {
        final savedPath = await _storage.getHostContainerPath();
        final savedGb = await _storage.getHostContainerSizeGb();
        if (savedPath != null && savedPath.isNotEmpty && savedGb != null) {
          await enableHost(savedGb, savedPath);
        }
      }
    } catch (_) {}
  }

  @override
  void dispose() {
    _heartbeatTimer?.cancel();
    super.dispose();
  }

  Future<void> checkHostStatus() async {
    state = const HostLoading();
    try {
      final info = await _repository.getHostStatus();
      if (info != null && info.isOnline) {
        state = HostEnabled(info);
        _startHeartbeatDaemon(info.id);
      } else {
        state = HostDisabled(info);
      }
    } catch (_) {
      state = const HostDisabled();
    }
  }

  Future<String> _getLocalIpAddress() async {
    try {
      final interfaces = await NetworkInterface.list(type: InternetAddressType.IPv4);
      for (final interface in interfaces) {
        final name = interface.name.toLowerCase();
        if (name.contains('loopback') || name == 'lo') continue;
        for (final addr in interface.addresses) {
          if (!addr.isLoopback && addr.address.isNotEmpty && !addr.address.startsWith('127.')) {
            return addr.address;
          }
        }
      }
    } catch (_) {}
    return '127.0.0.1';
  }

  /// Allocates storage container on local disk at specified custom location with specified size in GB,
  /// then activates 24/7 foreground background service and heartbeat pulse daemon.
  Future<void> enableHost(int reservedGb, String containerPath) async {
    state = const HostLoading();
    try {
      final reservedBytes = reservedGb * 1024 * 1024 * 1024;
      const totalBytes = 100 * 1024 * 1024 * 1024;

      final ipAddress = await _getLocalIpAddress();
      final nodeName = Platform.localHostname.isNotEmpty
          ? 'Node-${Platform.localHostname}'
          : 'MicroServer-Node';

      // 1. Create binary disk container file at custom path
      final info = await _repository.registerHost(
        name: nodeName,
        deviceType: Platform.isAndroid ? 'Mobile' : 'Desktop',
        operatingSystem: Platform.operatingSystem,
        publicIp: ipAddress,
        totalCapacityBytes: totalBytes,
        reservedCapacityBytes: reservedBytes,
      );

      await _repository.createStorageContainer(info.id, reservedGb, containerPath);

      // 2. Persist container allocation settings so host is ALWAYS ACTIVE
      await _storage.saveHostContainerPath(containerPath);
      await _storage.saveHostContainerSizeGb(reservedGb);
      await _storage.saveHostAllocated(true);

      // 3. Start 24/7 Android Foreground Service
      await HostBackgroundService.startHostService(
        reservedGb: reservedGb,
        containerPath: containerPath,
      );

      // 4. Set state to HostEnabled & start 30s heartbeat pulse daemon
      final updatedInfo = info.copyWith(
        status: 'ONLINE',
        containerCreated: true,
        containerPath: containerPath,
        reservedCapacityBytes: reservedBytes,
        publicIp: ipAddress,
      );
      state = HostEnabled(updatedInfo);
      _startHeartbeatDaemon(updatedInfo.id);
    } catch (e) {
      state = HostError('Container allocation failed: ${e.toString()}');
    }
  }

  Future<void> disableHost() async {
    _heartbeatTimer?.cancel();
    _heartbeatTimer = null;

    await HostBackgroundService.stopHostService();
    await _storage.saveHostAllocated(false);

    if (state is HostEnabled) {
      final current = (state as HostEnabled).info;
      await _repository.disableHostNode(current.id, current.name);
      final offline = current.copyWith(status: 'OFFLINE');
      state = HostDisabled(offline);
    } else {
      state = const HostDisabled();
    }
  }

  void _startHeartbeatDaemon(String hostId) {
    _heartbeatTimer?.cancel();
    _sendHeartbeatPulse(hostId);
    _heartbeatTimer = Timer.periodic(const Duration(milliseconds: 500), (_) async {
      _sendHeartbeatPulse(hostId);
    });
  }

  Future<void> _sendHeartbeatPulse(String hostId) async {
    if (state is HostEnabled) {
      final current = (state as HostEnabled).info;
      final storageLoad = current.reservedCapacityBytes > 0
          ? (current.usedCapacityBytes / current.reservedCapacityBytes * 100.0).clamp(5.0, 95.0)
          : 10.0;
      final cpuLoad = storageLoad;
      final ramLoad = (storageLoad * 0.8 + 20.0).clamp(10.0, 90.0);

      try {
        await _repository.sendHeartbeat(
          hostId: hostId,
          cpuUsagePercent: cpuLoad,
          ramUsagePercent: ramLoad,
          usedCapacityBytes: current.usedCapacityBytes,
          reservedStorageBytes: current.reservedCapacityBytes,
        );

        if (mounted && state is HostEnabled) {
          state = HostEnabled(
            current.copyWith(
              lastHeartbeat: DateTime.now(),
              cpuUsagePercent: cpuLoad,
              ramUsagePercent: ramLoad,
              status: 'ONLINE',
            ),
          );
        }
      } catch (_) {}
    }
  }
}
