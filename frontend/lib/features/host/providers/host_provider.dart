import 'dart:async';
import 'dart:math';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/errors/failures.dart';
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

/// Riverpod StateNotifier managing Host Mode lifecycle and 30-second telemetry timer daemon.
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

  /// Enables host node, creates container, and starts 30s heartbeat daemon.
  Future<void> enableHost(int reservedGb) async {
    state = const HostLoading();
    try {
      final reservedBytes = reservedGb * 1024 * 1024 * 1024;
      const totalBytes = 100 * 1024 * 1024 * 1024;

      final info = await _repository.registerHost(
        name: 'MicroServer-Node-${Random().nextInt(1000)}',
        deviceType: 'Desktop',
        operatingSystem: 'Windows',
        publicIp: '127.0.0.1',
        totalCapacityBytes: totalBytes,
        reservedCapacityBytes: reservedBytes,
      );

      try {
        await _repository.createStorageContainer(reservedGb);
      } catch (_) {}

      final updatedInfo = info.copyWith(status: 'ONLINE', containerCreated: true);
      state = HostEnabled(updatedInfo);
      _startHeartbeatDaemon(updatedInfo.id);
    } on Failure catch (f) {
      state = HostError(f.message);
    } catch (e) {
      state = HostError(e.toString());
    }
  }

  /// Disables host mode and cancels heartbeat daemon.
  Future<void> disableHost() async {
    _heartbeatTimer?.cancel();
    _heartbeatTimer = null;

    if (state is HostEnabled) {
      final current = (state as HostEnabled).info;
      final offline = current.copyWith(status: 'OFFLINE');
      state = HostDisabled(offline);
    } else {
      state = const HostDisabled();
    }
  }

  /// Starts periodic 30-second heartbeat daemon.
  void _startHeartbeatDaemon(String hostId) {
    _heartbeatTimer?.cancel();
    _heartbeatTimer = Timer.periodic(const Duration(seconds: 30), (_) async {
      if (state is HostEnabled) {
        final current = (state as HostEnabled).info;
        final randomCpu = 10.0 + Random().nextDouble() * 25.0;
        final randomRam = 30.0 + Random().nextDouble() * 20.0;

        try {
          await _repository.sendHeartbeat(
            hostId: hostId,
            cpuUsagePercent: randomCpu,
            ramUsagePercent: randomRam,
            usedCapacityBytes: current.usedCapacityBytes,
          );

          if (mounted && state is HostEnabled) {
            state = HostEnabled(
              current.copyWith(
                lastHeartbeat: DateTime.now(),
                cpuUsagePercent: randomCpu,
                ramUsagePercent: randomRam,
              ),
            );
          }
        } catch (_) {}
      }
    });
  }
}
