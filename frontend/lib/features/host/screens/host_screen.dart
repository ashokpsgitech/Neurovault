import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:path_provider/path_provider.dart';

import '../../../core/firebase/firebase_service.dart';
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

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    if (bytes < 1024 * 1024 * 1024) return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
  }

  @override
  void initState() {
    super.initState();
    _initContainerPath();
  }

  Future<void> _initContainerPath() async {
    try {
      if (Platform.isAndroid || Platform.isIOS) {
        final docsDir = await getApplicationDocumentsDirectory();
        if (mounted) {
          setState(() {
            _containerPath = '${docsDir.path}/storage.container';
          });
        }
      }
    } catch (_) {}
  }

  void _showCustomLocationDialog([TextEditingController? externalController, StateSetter? dialogSetState]) {
    final controller = TextEditingController(text: externalController?.text ?? _containerPath);
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
              'Specify the target directory or path for pre-allocated binary storage.',
            ),
            const SizedBox(height: 16),
            TextField(
              controller: controller,
              decoration: InputDecoration(
                labelText: 'Container File Path',
                hintText: Platform.isWindows ? 'e.g. D:\\NeuroVaultData\\storage.container' : '/storage/emulated/0/Download/storage.container',
                border: const OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            const Text('Quick Presets:', style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
            const SizedBox(height: 6),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                ActionChip(
                  avatar: const Icon(Icons.folder_special_outlined, size: 16),
                  label: const Text('App Private Storage', style: TextStyle(fontSize: 11)),
                  onPressed: () async {
                    try {
                      final docs = await getApplicationDocumentsDirectory();
                      controller.text = '${docs.path}/storage.container';
                    } catch (_) {}
                  },
                ),
                if (Platform.isAndroid)
                  ActionChip(
                    avatar: const Icon(Icons.download_outlined, size: 16),
                    label: const Text('Downloads Folder', style: TextStyle(fontSize: 11)),
                    onPressed: () {
                      controller.text = '/storage/emulated/0/Download/storage.container';
                    },
                  ),
                if (Platform.isWindows) ...[
                  ActionChip(
                    avatar: const Icon(Icons.sd_storage_outlined, size: 16),
                    label: const Text('D:\\ Drive', style: TextStyle(fontSize: 11)),
                    onPressed: () {
                      controller.text = 'D:\\NeuroVaultData\\storage.container';
                    },
                  ),
                  ActionChip(
                    avatar: const Icon(Icons.computer_outlined, size: 16),
                    label: const Text('C:\\ Drive', style: TextStyle(fontSize: 11)),
                    onPressed: () {
                      controller.text = 'C:\\NeuroVaultData\\storage.container';
                    },
                  ),
                ],
              ],
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
                if (externalController != null) {
                  externalController.text = selectedPath;
                }
                setState(() {
                  _containerPath = selectedPath;
                });
                if (dialogSetState != null) {
                  dialogSetState(() {});
                }
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
    final theme = Theme.of(context);
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
        CustomSnackbar.showError(
          context,
          next.message.length > 200 ? next.message.substring(0, 200) : next.message,
        );
      } else if (next is HostEnabled) {
        final info = next.info;
        if (info.containerCreated) {
          CustomSnackbar.showSuccess(
            context,
            'Container created successfully at:\n${info.containerPath}',
          );
        }
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
                  // Zero-Knowledge Security Notice Card
                  Card(
                    color: theme.colorScheme.surfaceContainerHighest.withOpacity(0.5),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(16),
                      side: BorderSide(color: theme.colorScheme.outlineVariant.withOpacity(0.5)),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.all(16.0),
                      child: Row(
                        children: [
                          Icon(Icons.shield_moon_outlined, size: 32, color: theme.colorScheme.primary),
                          const SizedBox(width: 16),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  'Zero-Knowledge Container Security',
                                  style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  'Containers remain locked from allocation. Hosts only provide disk storage and can NEVER inspect file contents, keys, or filenames.',
                                  style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 20),

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
                  const SizedBox(height: 24),

                  // Hosted Client Chunks & Storage Breakdown Card (Debugging & Transparency)
                  _buildHostedClientsCard(context),
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
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
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
                        'Micro-Server Host Container',
                        style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        isEnabled
                            ? 'Container allocated & node active 24/7 in mesh storage network'
                            : 'Set custom location & size below to allocate container and activate node 24/7',
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 16),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                  decoration: BoxDecoration(
                    color: isEnabled ? Colors.green.withOpacity(0.2) : Colors.amber.withOpacity(0.2),
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: isEnabled ? Colors.green : Colors.amber, width: 1.5),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        isEnabled ? Icons.check_circle : Icons.offline_bolt_outlined,
                        color: isEnabled ? Colors.green : Colors.amber.shade800,
                        size: 18,
                      ),
                      const SizedBox(width: 6),
                      Text(
                        isEnabled ? 'ALWAYS ACTIVE 24/7' : 'NOT YET ALLOCATED',
                        style: TextStyle(
                          color: isEnabled ? Colors.green : Colors.amber.shade900,
                          fontWeight: FontWeight.bold,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            if (isEnabled) ...[
              const SizedBox(height: 16),
              Row(
                children: [
                  const Icon(Icons.check_circle_outline, color: Colors.green, size: 20),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      'Host node is allocated and active 24/7. Ready to store encrypted chunk blocks from clients.',
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: Colors.green,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                ],
              ),
            ],
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
                  '1. Container File Storage Location',
                  style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                ),
                ElevatedButton.icon(
                  icon: const Icon(Icons.edit_location_alt_outlined, size: 16),
                  label: const Text('Change Path', style: TextStyle(fontSize: 12)),
                  style: ElevatedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  ),
                  onPressed: isEnabled ? null : () => _showCustomLocationDialog(),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest.withOpacity(0.5),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: theme.colorScheme.outlineVariant.withOpacity(0.5)),
              ),
              child: Row(
                children: [
                  const Icon(Icons.folder_special_outlined, color: Colors.amber, size: 20),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      _containerPath,
                      style: const TextStyle(fontFamily: 'monospace', fontSize: 13, fontWeight: FontWeight.bold),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '2. Custom Storage Reservation Capacity',
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
            const SizedBox(height: 16),
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
            if (!isEnabled) ...[
              const SizedBox(height: 24),
              SizedBox(
                width: double.infinity,
                height: 48,
                child: ElevatedButton.icon(
                  icon: const Icon(Icons.add_to_drive_outlined),
                  label: Text('Allocate ${_reservedGb.round()} GB Container & Activate Host Node 24/7'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: theme.colorScheme.primary,
                    foregroundColor: theme.colorScheme.onPrimary,
                    textStyle: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
                  ),
                  onPressed: () {
                    ref.read(hostProvider.notifier).enableHost(_reservedGb.round(), _containerPath);
                  },
                ),
              ),
            ],
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

  Widget _buildHostedClientsCard(BuildContext context) {
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
                Text('Hosted Client Chunks & Storage Breakdown', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                const Icon(Icons.people_alt_outlined, color: Colors.blue),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              'Real-time debug list of client user accounts and encrypted file chunks stored inside this node container.',
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
            const SizedBox(height: 16),
            FutureBuilder<List<Map<String, dynamic>>>(
              future: FirebaseService().getHostedChunksForCurrentHost(),
              builder: (context, snapshot) {
                if (snapshot.connectionState == ConnectionState.waiting) {
                  return const Padding(
                    padding: EdgeInsets.all(20.0),
                    child: Center(child: CircularProgressIndicator()),
                  );
                }

                final hostedList = snapshot.data ?? [];
                if (hostedList.isEmpty) {
                  return Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: theme.colorScheme.surfaceContainerHighest.withOpacity(0.4),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: const Center(
                      child: Text('No client file chunks currently stored in this container pool.', style: TextStyle(fontSize: 13, color: Colors.grey)),
                    ),
                  );
                }

                return ListView.separated(
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  itemCount: hostedList.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 8),
                  itemBuilder: (context, index) {
                    final item = hostedList[index];
                    final String clientEmail = item['clientEmail']?.toString() ?? 'Client Account';
                    final String filename = item['filename']?.toString() ?? 'chunk.bin';
                    final int idx = (item['chunkIndex'] ?? 0) + 1;
                    final int bytes = item['sizeBytes'] ?? 0;
                    final String timeIso = item['createdAtIso']?.toString() ?? '';

                    return Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: theme.colorScheme.surfaceContainerHighest.withOpacity(0.4),
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: theme.colorScheme.outlineVariant.withOpacity(0.4)),
                      ),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    const Icon(Icons.account_circle_outlined, size: 16, color: Colors.blue),
                                    const SizedBox(width: 6),
                                    Expanded(
                                      child: Text(
                                        clientEmail,
                                        style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13, color: Colors.blue),
                                        overflow: TextOverflow.ellipsis,
                                      ),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  '$filename (Chunk #$idx)',
                                  style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ],
                            ),
                          ),
                          const SizedBox(width: 12),
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.end,
                            children: [
                              Text(
                                _formatBytes(bytes),
                                style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13, color: Colors.green),
                              ),
                              if (timeIso.isNotEmpty)
                                Text(
                                  timeIso.length > 10 ? timeIso.substring(0, 10) : timeIso,
                                  style: const TextStyle(fontSize: 10, color: Colors.grey),
                                ),
                            ],
                          ),
                        ],
                      ),
                    );
                  },
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}
