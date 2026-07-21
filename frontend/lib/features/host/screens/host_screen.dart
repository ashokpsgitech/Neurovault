import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../widgets/custom_snackbar.dart';
import '../../../widgets/loading_overlay.dart';
import '../models/host_info_model.dart';
import '../providers/host_provider.dart';
import '../providers/host_state.dart';

/// Responsive Material 3 Host Mode Screen for NeuroVault.
class HostScreen extends ConsumerStatefulWidget {
  const HostScreen({super.key});

  @override
  ConsumerState<HostScreen> createState() => _HostScreenState();
}

class _HostScreenState extends ConsumerState<HostScreen> {
  double _reservedGb = 10.0;

  @override
  Widget build(BuildContext context) {
    final hostState = ref.watch(hostProvider);
    final isLoading = hostState is HostLoading;
    final isEnabled = hostState is HostEnabled;

    HostInfoModel? hostInfo;
    if (hostState is HostEnabled) {
      hostInfo = hostState.info;
    } else if (hostState is HostDisabled) {
      hostInfo = hostState.info;
    }

    ref.listen<HostState>(hostProvider, (previous, next) {
      if (next is HostError) {
        CustomSnackbar.showError(context, next.message);
      }
    });

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.go('/dashboard'),
        ),
        title: const Text('Host Mode Subsystem', style: TextStyle(fontWeight: FontWeight.bold)),
      ),
      body: LoadingOverlay(
        isLoading: isLoading,
        message: 'Configuring Micro-Server Node...',
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 1000),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Master Control Switch Card
                  _buildMasterToggleCard(context, isEnabled, isLoading),
                  const SizedBox(height: 24),

                  // Storage Reservation Control Card
                  _buildReservationCard(context, isEnabled),
                  const SizedBox(height: 24),

                  // Telemetry & Container Metric Cards Grid
                  LayoutBuilder(
                    builder: (context, constraints) {
                      final isDesktop = constraints.maxWidth >= 768;
                      if (isDesktop) {
                        return Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Expanded(child: _buildTelemetryCard(context, isEnabled, hostInfo)),
                            const SizedBox(width: 16),
                            Expanded(child: _buildContainerStatusCard(context, isEnabled, hostInfo)),
                          ],
                        );
                      } else {
                        return Column(
                          children: [
                            _buildTelemetryCard(context, isEnabled, hostInfo),
                            const SizedBox(height: 16),
                            _buildContainerStatusCard(context, isEnabled, hostInfo),
                          ],
                        );
                      }
                    },
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildMasterToggleCard(BuildContext context, bool isEnabled, bool isLoading) {
    final theme = Theme.of(context);
    return Card(
      elevation: 2,
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: isEnabled ? Colors.green.withOpacity(0.15) : theme.colorScheme.primary.withOpacity(0.1),
                shape: BoxShape.circle,
              ),
              child: Icon(
                Icons.storage_outlined,
                size: 36,
                color: isEnabled ? Colors.green : theme.colorScheme.primary,
              ),
            ),
            const SizedBox(width: 20),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Micro-Server Host Mode',
                    style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    isEnabled
                        ? 'Active node contributing storage to NeuroVault mesh network'
                        : 'Enable to contribute storage capacity as a decentralized node',
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 16),
            Switch(
              value: isEnabled,
              activeColor: Colors.green,
              onChanged: isLoading
                  ? null
                  : (value) {
                      if (value) {
                        ref.read(hostProvider.notifier).enableHost(_reservedGb.round());
                        CustomSnackbar.showSuccess(context, 'Host Mode Enabled: Online');
                      } else {
                        ref.read(hostProvider.notifier).disableHost();
                        CustomSnackbar.showSuccess(context, 'Host Mode Disabled: Offline');
                      }
                    },
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildReservationCard(BuildContext context, bool isEnabled) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'Storage Reservation Limit',
                  style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                ),
                Chip(
                  label: Text(
                    '${_reservedGb.round()} GB',
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                  backgroundColor: theme.colorScheme.primary.withOpacity(0.15),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              'Select how much disk space your node pre-allocates for encrypted chunk blocks.',
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
            const SizedBox(height: 20),
            Slider(
              value: _reservedGb,
              min: 1.0,
              max: 100.0,
              divisions: 99,
              label: '${_reservedGb.round()} GB',
              onChanged: isEnabled
                  ? null
                  : (value) {
                      setState(() {
                        _reservedGb = value;
                      });
                    },
            ),
            const Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('1 GB', style: TextStyle(fontSize: 12)),
                Text('50 GB', style: TextStyle(fontSize: 12)),
                Text('100 GB', style: TextStyle(fontSize: 12)),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTelemetryCard(BuildContext context, bool isEnabled, HostInfoModel? hostInfo) {
    final theme = Theme.of(context);
    final lastHeartbeatStr = hostInfo?.lastHeartbeat != null
        ? DateFormat('hh:mm:ss a').format(hostInfo!.lastHeartbeat!)
        : 'None';

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('Telemetry & Heartbeat', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                Icon(Icons.favorite_outlined, color: isEnabled ? Colors.red.shade400 : Colors.grey),
              ],
            ),
            const SizedBox(height: 16),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.speed_outlined),
              title: const Text('CPU Utilization'),
              trailing: Text(
                isEnabled ? '${(hostInfo?.cpuUsagePercent ?? 12.5).toStringAsFixed(1)}%' : '0.0%',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
            ),
            const Divider(height: 1),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.memory_outlined),
              title: const Text('RAM Usage'),
              trailing: Text(
                isEnabled ? '${(hostInfo?.ramUsagePercent ?? 38.2).toStringAsFixed(1)}%' : '0.0%',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
            ),
            const Divider(height: 1),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.timer_outlined),
              title: const Text('Heartbeat Pulse'),
              subtitle: Text('Last: $lastHeartbeatStr'),
              trailing: Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                  color: isEnabled ? Colors.green.withOpacity(0.15) : Colors.grey.withOpacity(0.15),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  isEnabled ? '30s Active' : 'Stopped',
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    color: isEnabled ? Colors.green.shade400 : Colors.grey,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildContainerStatusCard(BuildContext context, bool isEnabled, HostInfoModel? hostInfo) {
    final theme = Theme.of(context);

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('Binary Container', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                Icon(Icons.inventory_2_outlined, color: theme.colorScheme.primary),
              ],
            ),
            const SizedBox(height: 16),
            const ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(Icons.insert_drive_file_outlined),
              title: Text('Container File Path'),
              subtitle: Text('./neurovault-storage/storage.container'),
            ),
            const Divider(height: 1),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.data_usage_outlined),
              title: const Text('Allocated Capacity'),
              trailing: Text(
                isEnabled ? '${_reservedGb.round()} GB' : '0 GB',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
            ),
            const Divider(height: 1),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.grid_view_outlined),
              title: const Text('Active Chunks Stored'),
              trailing: Text(
                '${hostInfo?.activeChunks ?? 0}',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
