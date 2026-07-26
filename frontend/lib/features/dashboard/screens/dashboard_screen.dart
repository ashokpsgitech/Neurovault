import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../providers/core_providers.dart';
import '../../../widgets/custom_snackbar.dart';
import '../../../widgets/debug_console_modal.dart';
import '../../authentication/models/user_model.dart';
import '../../authentication/providers/auth_provider.dart';
import '../../host/providers/host_provider.dart';
import '../../host/providers/host_state.dart';
import '../models/dashboard_stats_model.dart';
import '../providers/dashboard_provider.dart';
import '../providers/dashboard_state.dart';

/// Responsive Material 3 Dashboard Screen for NeuroVault.
class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  String _formatBytes(int bytes) {
    if (bytes <= 0) return '0 B';
    const suffixes = ['B', 'KB', 'MB', 'GB', 'TB'];
    int i = 0;
    double count = bytes.toDouble();
    while (count >= 1024 && i < suffixes.length - 1) {
      count /= 1024;
      i++;
    }
    return '${count.toStringAsFixed(1)} ${suffixes[i]}';
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final isDark = ref.watch(themeModeProvider) == ThemeMode.dark;
    final dashboardState = ref.watch(dashboardProvider);

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Icon(Icons.shield_outlined, color: theme.colorScheme.primary),
            const SizedBox(width: 12),
            const Text(
              'NeuroVault',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.bug_report_outlined, color: Colors.orange),
            tooltip: 'In-App Debug Logs',
            onPressed: () => DebugConsoleModal.show(context),
          ),
          IconButton(
            icon: Icon(isDark ? Icons.light_mode_outlined : Icons.dark_mode_outlined),
            tooltip: 'Toggle Theme',
            onPressed: () {
              ref.read(themeModeProvider.notifier).state =
                  isDark ? ThemeMode.light : ThemeMode.dark;
            },
          ),
          IconButton(
            icon: const Icon(Icons.refresh_outlined),
            tooltip: 'Refresh Metrics',
            onPressed: () {
              ref.read(dashboardProvider.notifier).refresh();
            },
          ),
          IconButton(
            icon: const Icon(Icons.logout_outlined),
            tooltip: 'Sign Out',
            onPressed: () async {
              await ref.read(authStateProvider.notifier).logout();
              if (context.mounted) {
                CustomSnackbar.showSuccess(context, 'Signed out successfully');
                context.go('/login');
              }
            },
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: Builder(
        builder: (context) {
          if (dashboardState is DashboardLoading || dashboardState is DashboardInitial) {
            return const Center(child: CircularProgressIndicator());
          }
          if (dashboardState is DashboardError) {
            return Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.error_outline, size: 48, color: theme.colorScheme.error),
                  const SizedBox(height: 16),
                  Text(dashboardState.message, style: theme.textTheme.titleMedium),
                  const SizedBox(height: 16),
                  ElevatedButton(
                    onPressed: () => ref.read(dashboardProvider.notifier).refresh(),
                    child: const Text('Retry'),
                  ),
                ],
              ),
            );
          }

          final stats = (dashboardState as DashboardLoaded).stats;

          return LayoutBuilder(
            builder: (context, constraints) {
              final isDesktop = constraints.maxWidth >= 900;
              return SingleChildScrollView(
                padding: const EdgeInsets.all(24.0),
                child: Center(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 1200),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // User Profile Greeting Header
                        _buildUserGreetingHeader(context, stats),
                        const SizedBox(height: 24),

                        // Metric Stat Cards Grid
                        _buildStatCards(context, ref, stats, isDesktop),
                        const SizedBox(height: 32),

                        // Quick Actions Section
                        Text(
                          'Quick Actions',
                          style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                        ),
                        const SizedBox(height: 16),
                        _buildQuickActionsGrid(context, ref, isDesktop, stats.user),
                        const SizedBox(height: 32),

                        // Recent Activity Section
                        Text(
                          'Recent Activity',
                          style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                        ),
                        const SizedBox(height: 16),
                        _buildRecentActivityList(context, stats),
                      ],
                    ),
                  ),
                ),
              );
            },
          );
        },
      ),
    );
  }

  Widget _buildStatCards(BuildContext context, WidgetRef ref, DashboardStatsModel stats, bool isDesktop) {
    final user = stats.user;
    final isPublicHost = user.mode == 'PUBLIC' && user.role == 'HOST';
    final isPublicClient = user.mode == 'PUBLIC' && user.role == 'CLIENT';

    List<Widget> cards = [];

    if (isPublicHost) {
      cards = [
        _buildActiveUsersCard(context, stats),
        _buildHostClientStorageCard(context, stats),
        _buildHostStatusCard(context, ref, stats),
      ];
    } else if (isPublicClient) {
      cards = [
        _buildHostStatusCard(context, ref, stats),
        _buildStorageCard(context, stats),
        _buildFilesCard(context, stats),
      ];
    } else {
      // Private User or Role: BOTH
      cards = [
        _buildStorageCard(context, stats),
        _buildHostStatusCard(context, ref, stats),
        _buildFilesCard(context, stats),
        _buildActiveUsersCard(context, stats),
      ];
    }

    if (isDesktop) {
      return Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: cards.map((c) => Expanded(child: Padding(padding: const EdgeInsets.only(right: 12), child: c))).toList(),
      );
    }

    return Column(
      children: cards.map((c) => Padding(padding: const EdgeInsets.only(bottom: 16), child: c)).toList(),
    );
  }

  Widget _buildActiveUsersCard(BuildContext context, DashboardStatsModel stats) {
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
                Text('Active Users Using Host', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                const Icon(Icons.people_outline, color: Colors.green),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Text(
                  '${stats.activeUsersCount}',
                  style: theme.textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.bold, color: Colors.green),
                ),
                const SizedBox(width: 8),
                Text('Connected Client(s)', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              'Clients currently storing chunk replicas on this host',
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHostClientStorageCard(BuildContext context, DashboardStatsModel stats) {
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
                Text('Storage Used by Clients', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                const Icon(Icons.sd_storage_outlined, color: Colors.orange),
              ],
            ),
            const SizedBox(height: 16),
            LinearProgressIndicator(
              value: stats.reservedStorageBytes > 0 ? (stats.hostStorageUsedBytes / stats.reservedStorageBytes).clamp(0.0, 1.0) : 0.0,
              minHeight: 8,
              borderRadius: BorderRadius.circular(4),
              backgroundColor: Colors.orange.withOpacity(0.15),
            ),
            const SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '${_formatBytes(stats.hostStorageUsedBytes)} used by clients',
                  style: theme.textTheme.bodySmall?.copyWith(fontWeight: FontWeight.bold),
                ),
                Text(
                  '${_formatBytes(stats.reservedStorageBytes)} container limit',
                  style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildUserGreetingHeader(BuildContext context, DashboardStatsModel stats) {
    final theme = Theme.of(context);
    final isPublic = stats.user.mode == 'PUBLIC';

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Row(
          children: [
            CircleAvatar(
              radius: 28,
              backgroundColor: isPublic ? Colors.purple.withOpacity(0.15) : theme.colorScheme.primary.withOpacity(0.15),
              child: Text(
                stats.user.username.isNotEmpty ? stats.user.username[0].toUpperCase() : 'U',
                style: theme.textTheme.headlineMedium?.copyWith(
                  color: isPublic ? Colors.purple : theme.colorScheme.primary,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
            const SizedBox(width: 20),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(
                        'Welcome back, ${stats.user.username}!',
                        style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold),
                      ),
                      if (stats.user.isAnonymous) ...[
                        const SizedBox(width: 8),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                          decoration: BoxDecoration(
                            color: Colors.purple.withOpacity(0.15),
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: const Text('Anonymous', style: TextStyle(fontSize: 10, fontWeight: FontWeight.bold, color: Colors.purple)),
                        ),
                      ],
                    ],
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      Chip(
                        label: Text('Role: ${stats.user.role}', style: const TextStyle(fontSize: 11, fontWeight: FontWeight.bold)),
                        padding: EdgeInsets.zero,
                        visualDensity: VisualDensity.compact,
                      ),
                      const SizedBox(width: 8),
                      Chip(
                        avatar: Icon(isPublic ? Icons.public : Icons.lock_outline, size: 14),
                        label: Text('${stats.user.mode} Mode', style: const TextStyle(fontSize: 11, fontWeight: FontWeight.bold)),
                        backgroundColor: isPublic ? Colors.purple.withOpacity(0.12) : theme.colorScheme.primary.withOpacity(0.12),
                        padding: EdgeInsets.zero,
                        visualDensity: VisualDensity.compact,
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStorageCard(BuildContext context, DashboardStatsModel stats) {
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
                Text('Storage Used by Client', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                Icon(Icons.pie_chart_outline, color: theme.colorScheme.primary),
              ],
            ),
            const SizedBox(height: 16),
            LinearProgressIndicator(
              value: stats.storageUsagePercent,
              minHeight: 8,
              borderRadius: BorderRadius.circular(4),
              backgroundColor: theme.colorScheme.primary.withOpacity(0.1),
            ),
            const SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '${_formatBytes(stats.storageUsedBytes)} used',
                  style: theme.textTheme.bodySmall?.copyWith(fontWeight: FontWeight.bold),
                ),
                Text(
                  '${_formatBytes(stats.storageCapacityBytes)} total',
                  style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHostStatusCard(BuildContext context, WidgetRef ref, DashboardStatsModel stats) {
    final theme = Theme.of(context);
    final hostState = ref.watch(hostProvider);
    final isHostActive = hostState is HostEnabled;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('Active Mesh Hosts', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                Icon(Icons.dns_outlined, color: isHostActive ? Colors.green : theme.colorScheme.primary),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Text(
                  '${stats.activeHostsCount}',
                  style: theme.textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.bold, color: theme.colorScheme.primary),
                ),
                const SizedBox(width: 8),
                Text('Active Node(s)', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              isHostActive ? 'This device is currently an active host node' : 'Active storage hosts available in mesh network',
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildFilesCard(BuildContext context, DashboardStatsModel stats) {
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
                Text('Files Uploaded', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                Icon(Icons.folder_outlined, color: theme.colorScheme.primary),
              ],
            ),
            const SizedBox(height: 16),
            Text(
              '${stats.totalFiles} File(s)',
              style: theme.textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 4),
            Text(
              'Split into 4MB Chunk Replicas',
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildQuickActionsGrid(BuildContext context, WidgetRef ref, bool isDesktop, UserModel user) {
    final hostState = ref.watch(hostProvider);
    final isHostActive = hostState is HostEnabled;
    final isPublicHost = user.mode == 'PUBLIC' && user.role == 'HOST';
    final isPublicClient = user.mode == 'PUBLIC' && user.role == 'CLIENT';

    List<_ActionItem> actions = [];

    if (isPublicHost) {
      actions = [
        _ActionItem('Host Subsystem', 'Manage container & node telemetry', Icons.storage_outlined, Colors.green, () {
          context.go('/host');
        }),
        _ActionItem('Node Telemetry', 'Monitor CPU, RAM & Disks', Icons.analytics_outlined, Colors.teal, () {
          context.go('/host');
        }),
        _ActionItem('Settings', 'Coordinator URL & interval', Icons.settings_outlined, Colors.purple, () {
          context.go('/settings');
        }),
      ];
    } else if (isPublicClient) {
      actions = [
        _ActionItem('Upload File', 'Encrypt & stream chunk blocks', Icons.cloud_upload_outlined, Colors.indigo, () {
          context.go('/files');
        }),
        _ActionItem('Download Files', 'Fetch & decrypt chunks', Icons.cloud_download_outlined, Colors.teal, () {
          context.go('/files');
        }),
        _ActionItem('Settings', 'Coordinator URL & interval', Icons.settings_outlined, Colors.purple, () {
          context.go('/settings');
        }),
      ];
    } else {
      // Private User or Role: BOTH
      if (isHostActive) {
        actions = [
          _ActionItem('Upload File', 'Encrypt & stream chunk blocks', Icons.cloud_upload_outlined, Colors.indigo, () {
            context.go('/files');
          }),
          _ActionItem('Download Files', 'Fetch & decrypt chunks', Icons.cloud_download_outlined, Colors.teal, () {
            context.go('/files');
          }),
          _ActionItem('Host Subsystem', 'Manage container & telemetry', Icons.storage_outlined, Colors.green, () {
            context.go('/host');
          }),
          _ActionItem('Settings', 'Coordinator URL & interval', Icons.settings_outlined, Colors.purple, () {
            context.go('/settings');
          }),
        ];
      } else {
        actions = [
          _ActionItem('Upload File', 'Encrypt & stream chunk blocks', Icons.cloud_upload_outlined, Colors.indigo, () {
            context.go('/files');
          }),
          _ActionItem('Download Files', 'Fetch & decrypt chunks', Icons.cloud_download_outlined, Colors.teal, () {
            context.go('/files');
          }),
          _ActionItem('Become Host', 'Share capacity as micro-server', Icons.storage_outlined, Colors.cyan, () {
            context.go('/host');
          }),
          _ActionItem('Settings', 'Coordinator URL & interval', Icons.settings_outlined, Colors.purple, () {
            context.go('/settings');
          }),
        ];
      }
    }

    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: isDesktop ? 4 : 2,
        crossAxisSpacing: 16,
        mainAxisSpacing: 16,
        childAspectRatio: isDesktop ? 1.3 : 1.1,
      ),
      itemCount: actions.length,
      itemBuilder: (context, index) {
        final item = actions[index];
        return Card(
          child: InkWell(
            borderRadius: BorderRadius.circular(16),
            onTap: item.onTap,
            child: Padding(
              padding: const EdgeInsets.all(16.0),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(item.icon, size: 36, color: item.color),
                  const SizedBox(height: 12),
                  Text(item.title, style: const TextStyle(fontWeight: FontWeight.bold), textAlign: TextAlign.center),
                  const SizedBox(height: 4),
                  Text(item.subtitle, style: Theme.of(context).textTheme.bodySmall, textAlign: TextAlign.center),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildRecentActivityList(BuildContext context, DashboardStatsModel stats) {
    if (stats.recentActivities.isEmpty) {
      return const Card(
        child: Padding(
          padding: EdgeInsets.all(24.0),
          child: Center(child: Text('No recent activity')),
        ),
      );
    }

    return Card(
      child: ListView.separated(
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        itemCount: stats.recentActivities.length,
        separatorBuilder: (_, __) => const Divider(height: 1),
        itemBuilder: (context, index) {
          final item = stats.recentActivities[index];
          return ListTile(
            leading: CircleAvatar(
              backgroundColor: Theme.of(context).colorScheme.primary.withOpacity(0.1),
              child: Icon(
                item.type == 'UPLOAD' ? Icons.arrow_upward : Icons.arrow_downward,
                color: Theme.of(context).colorScheme.primary,
                size: 20,
              ),
            ),
            title: Text(item.title, style: const TextStyle(fontWeight: FontWeight.bold)),
            subtitle: Text(item.subtitle),
            trailing: Text(
              DateFormat('hh:mm a').format(item.timestamp),
              style: Theme.of(context).textTheme.bodySmall,
            ),
          );
        },
      ),
    );
  }
}

class _ActionItem {
  final String title;
  final String subtitle;
  final IconData icon;
  final Color color;
  final VoidCallback onTap;

  _ActionItem(this.title, this.subtitle, this.icon, this.color, this.onTap);
}
