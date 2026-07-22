import 'package:file_picker/file_picker.dart';
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
  String _containerPath = 'D:\\NeuroVaultData\\storage.container';

  Future<void> _selectStorageLocation(TextEditingController controller, StateSetter? dialogSetState) async {
    try {
      final selectedDirectory = await FilePicker.platform.getDirectoryPath();
      if (selectedDirectory != null && selectedDirectory.isNotEmpty) {
        final formattedPath = selectedDirectory.replaceAll('/', '\\');
        final fullContainerPath = '$formattedPath\\storage.container';
        controller.text = fullContainerPath;
        setState(() {
          _containerPath = fullContainerPath;
        });
        if (dialogSetState != null) {
          dialogSetState(() {});
        }
        if (mounted) {
          CustomSnackbar.showSuccess(context, 'Container location set: $fullContainerPath');
        }
      }
    } catch (_) {
      if (mounted) {
        _showCustomLocationDialog();
      }
    }
  }

  void _showActivationDialog() {
    final controller = TextEditingController(text: _containerPath);
    showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Row(
            children: [
              Icon(Icons.storage_outlined, color: Colors.green),
              SizedBox(width: 12),
              Text('Activate Host Node'),
            ],
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Select local disk storage folder and confirm capacity reservation before activating your Micro-Server node.',
              ),
              const SizedBox(height: 16),
              Card(
                color: Theme.of(context).colorScheme.primary.withOpacity(0.08),
                child: Padding(
                  padding: const EdgeInsets.all(12.0),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Text('Reserved Capacity:'),
                      Text('${_reservedGb.round()} GB', style: const TextStyle(fontWeight: FontWeight.bold)),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: controller,
                decoration: InputDecoration(
                  labelText: 'Storage Container File Path',
                  hintText: 'e.g. D:\\NeuroVaultData\\storage.container',
                  suffixIcon: IconButton(
                    icon: const Icon(Icons.folder_open),
                    tooltip: 'Browse Directory',
                    onPressed: () => _selectStorageLocation(controller, setDialogState),
                  ),
                  border: const OutlineInputBorder(),
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Cancel'),
            ),
            ElevatedButton.icon(
              icon: const Icon(Icons.check_circle_outline),
              label: const Text('Activate Node & Allocate Storage'),
              onPressed: () {
                final targetPath = controller.text.trim().isNotEmpty
                    ? controller.text.trim()
                    : _containerPath;

                setState(() {
                  _containerPath = targetPath;
                });
                Navigator.pop(ctx);
                ref.read(hostProvider.notifier).enableHost(_reservedGb.round(), targetPath);
                CustomSnackbar.showSuccess(context, 'Allocating ${_reservedGb.round()} GB disk container at: $targetPath');
              },
            ),
          ],
        ),
      ),
    );
  }

  void _showCustomLocationDialog() {
    final controller = TextEditingController(text: _containerPath);
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.folder_open_outlined),
            SizedBox(width: 12),
            Text('Set Container Storage Location'),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Specify the local disk directory or container path for pre-allocated binary storage.',
            ),
            const SizedBox(height: 16),
            TextField(
              controller: controller,
              decoration: const InputDecoration(
                labelText: 'Container Path',
                hintText: 'e.g. D:\\NeuroVaultData\\storage.container',
                border: OutlineInputBorder(),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () {
              if (controller.text.trim().isNotEmpty) {
                final selectedPath = controller.text.trim();
                setState(() {
                  _containerPath = selectedPath;
                });
                Navigator.pop(ctx);
                CustomSnackbar.showSuccess(context, 'Storage location updated: $selectedPath');
              }
            },
            child: const Text('Save Location'),
          ),
        ],
      ),
    );
  }

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
        message: 'Configuring Micro-Server Node & Disk Container...',
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
                        _showActivationDialog();
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
    final containerCreated = hostInfo?.containerCreated ?? false;
    final displayPath = hostInfo?.containerPath.isNotEmpty == true ? hostInfo!.containerPath : _containerPath;
    final lockedSize = isEnabled && containerCreated
        ? hostInfo?.containerSizeDisplay ?? '${_reservedGb.round()} GB'
        : '0 GB';

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('Disk Container File', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                  decoration: BoxDecoration(
                    color: containerCreated && isEnabled
                        ? Colors.green.withOpacity(0.15)
                        : Colors.grey.withOpacity(0.15),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        containerCreated && isEnabled ? Icons.lock_outlined : Icons.lock_open_outlined,
                        size: 14,
                        color: containerCreated && isEnabled ? Colors.green : Colors.grey,
                      ),
                      const SizedBox(width: 4),
                      Text(
                        containerCreated && isEnabled ? 'LOCKED' : 'UNLOCKED',
                        style: TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.bold,
                          color: containerCreated && isEnabled ? Colors.green : Colors.grey,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            // Container file path
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(
                Icons.folder_special_outlined,
                color: containerCreated ? Colors.amber : Colors.grey,
              ),
              title: const Text('Container File Location', style: TextStyle(fontWeight: FontWeight.bold)),
              subtitle: Text(
                displayPath,
                style: TextStyle(
                  fontSize: 12,
                  fontFamily: 'monospace',
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
              trailing: isEnabled
                  ? null
                  : ElevatedButton.icon(
                      icon: const Icon(Icons.edit_location_alt_outlined, size: 16),
                      label: const Text('Change', style: TextStyle(fontSize: 12)),
                      style: ElevatedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                      ),
                      onPressed: _showCustomLocationDialog,
                    ),
            ),
            const Divider(height: 1),
            // Disk space locked
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(
                Icons.disc_full_outlined,
                color: containerCreated && isEnabled ? Colors.blue : Colors.grey,
              ),
              title: const Text('Disk Space Locked'),
              subtitle: containerCreated && isEnabled
                  ? Text(
                      'Pre-allocated binary file on local disk',
                      style: TextStyle(fontSize: 12, color: theme.colorScheme.onSurfaceVariant),
                    )
                  : null,
              trailing: Text(
                lockedSize,
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 16,
                  color: containerCreated && isEnabled ? Colors.blue : Colors.grey,
                ),
              ),
            ),
            if (containerCreated && isEnabled) ...[
              const Divider(height: 1),
              // Usage progress bar
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.storage_outlined, color: Colors.teal),
                title: const Text('Usage'),
                subtitle: Padding(
                  padding: const EdgeInsets.only(top: 6.0),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: LinearProgressIndicator(
                      value: hostInfo?.usagePercent ?? 0.0,
                      minHeight: 8,
                      backgroundColor: theme.colorScheme.surfaceContainerHighest,
                    ),
                  ),
                ),
                trailing: Text(
                  '${((hostInfo?.usagePercent ?? 0.0) * 100).toStringAsFixed(1)}%',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
              ),
            ],
            const Divider(height: 1),
            // Active chunks count
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(
                Icons.grid_view_outlined,
                color: (hostInfo?.activeChunks ?? 0) > 0 ? Colors.purple : Colors.grey,
              ),
              title: const Text('Active Encrypted Chunks'),
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
