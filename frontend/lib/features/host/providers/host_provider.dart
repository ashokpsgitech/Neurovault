import 'dart:async';
import 'dart:io';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/services/host_background_service.dart';
import '../../../providers/core_providers.dart';
import '../data/host_repository.dart';
import '../services/host_service.dart';
import 'host_state.dart';

final hostServiceProvider = Provider<HostService>((ref) {
  final dioClient = ref.watch(dioClientProvider);
  return HostService(dioClient);
});

final hostRepositoryProvider = Provider<HostRepository>((ref) {
  final service = ref.watch(hostServiceProvider);
  return HostRepository(service);
});

final hostProvider = StateNotifierProvider<HostNotifier, HostState>((ref) {
  final repo = ref.watch(hostRepositoryProvider);
  return HostNotifier(repo);
});

/// Riverpod StateNotifier managing Host Mode lifecycle, 24/7 Foreground Service, and heartbeat daemon.
class HostNotifier extends StateNotifier<HostState> {
  final HostRepository _repository;
  Timer? _heartbeatTimer;

  HostNotifier(this._repository) : super(const HostInitial()) {
    checkHostStatus();
  }

  @override
  void dispose() {
    _heartbeatTimer?.cancel();
    super.dispose();
  }

  /// Queries existing host status from backend.
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

  /// Resolves the primary local LAN IPv4 address of this device.
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

  /// Enables host node: registers with coordinator, creates disk container, starts foreground background service, and starts heartbeat.
  Future<void> enableHost(int reservedGb, String containerPath) async {
    state = const HostLoading();
    try {
      final reservedBytes = reservedGb * 1024 * 1024 * 1024;
      const totalBytes = 100 * 1024 * 1024 * 1024;

      final ipAddress = await _getLocalIpAddress();
      final nodeName = Platform.localHostname.isNotEmpty
          ? 'Node-${Platform.localHostname}'
          : 'MicroServer-Node';

      // Step 1: Register this device as a host node with the coordinator
      final info = await _repository.registerHost(
        name: nodeName,
        deviceType: Platform.isAndroid ? 'Mobile' : 'Desktop',
        operatingSystem: Platform.operatingSystem,
        publicIp: ipAddress,
        totalCapacityBytes: totalBytes,
        reservedCapacityBytes: reservedBytes,
      );

      // Step 2: Create pre-allocated binary storage container on disk
      await _repository.createStorageContainer(info.id, reservedGb, containerPath);

      // Step 3: Start 24/7 Android Foreground Service
      await HostBackgroundService.startHostService(
        reservedGb: reservedGb,
        containerPath: containerPath,
      );

      // Step 4: Update state to HostEnabled
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
      state = HostError('Container creation failed: ${e.toString()}');
    }
  }

  /// Disables host mode, stops background service, and cancels heartbeat daemon.
  Future<void> disableHost() async {
    _heartbeatTimer?.cancel();
    _heartbeatTimer = null;

    // Stop 24/7 Android Foreground Service
    await HostBackgroundService.stopHostService();

    if (state is HostEnabled) {
      final current = (state as HostEnabled).info;
      final offline = current.copyWith(status: 'OFFLINE');
      state = HostDisabled(offline);
    } else {
      state = const HostDisabled();
    }
  }

  /// Starts periodic 30-second heartbeat daemon and fires an immediate heartbeat pulse.
  void _startHeartbeatDaemon(String hostId) {
    _heartbeatTimer?.cancel();
    _sendHeartbeatPulse(hostId);
    _heartbeatTimer = Timer.periodic(const Duration(seconds: 30), (_) async {
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
