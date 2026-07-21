import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../providers/core_providers.dart';
import '../../../widgets/custom_snackbar.dart';
import '../../authentication/providers/auth_provider.dart';
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
                        if (isDesktop)
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Expanded(child: _buildStorageCard(context, stats)),
                              const SizedBox(width: 16),
                              Expanded(child: _buildHostStatusCard(context, stats)),
                              const SizedBox(width: 16),
                              Expanded(child: _buildFilesCard(context, stats)),
                            ],
                          )
                        else ...[
                          _buildStorageCard(context, stats),
                          const SizedBox(height: 16),
                          _buildHostStatusCard(context, stats),
                          const SizedBox(height: 16),
                          _buildFilesCard(context, stats),
                        ],
                        const SizedBox(height: 32),

                        // Quick Actions Section
                        Text(
                          'Quick Actions',
                          style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                        ),
                        const SizedBox(height: 16),
                        _buildQuickActionsGrid(context, isDesktop),
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

  Widget _buildUserGreetingHeader(BuildContext context, DashboardStatsModel stats) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Row(
          children: [
            CircleAvatar(
              radius: 28,
              backgroundColor: theme.colorScheme.primary.withOpacity(0.15),
              child: Text(
                stats.user.username.isNotEmpty ? stats.user.username[0].toUpperCase() : 'U',
                style: theme.textTheme.headlineMedium?.copyWith(
                  color: theme.colorScheme.primary,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
            const SizedBox(width: 20),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Welcome back, ${stats.user.username}!',
                    style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${stats.user.email}  •  Role: ${stats.user.role}',
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
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
                Text('Storage Usage', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
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

  Widget _buildHostStatusCard(BuildContext context, DashboardStatsModel stats) {
    final theme = Theme.of(context);
    final isOnline = stats.hostStatus == 'ONLINE';

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('Host Node Status', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                Icon(Icons.dns_outlined, color: isOnline ? Colors.green : theme.colorScheme.primary),
              ],
            ),
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(
                color: isOnline ? Colors.green.withOpacity(0.15) : Colors.orange.withOpacity(0.15),
                borderRadius: BorderRadius.circular(20),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  CircleAvatar(
                    radius: 4,
                    backgroundColor: isOnline ? Colors.green : Colors.orange,
                  ),
                  const SizedBox(width: 8),
                  Text(
                    stats.hostStatus,
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      color: isOnline ? Colors.green.shade400 : Colors.orange.shade400,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),
            Text(
              isOnline ? 'Active Micro-Server node' : 'Not registered as storage host',
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
                Text('Vault Files', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                Icon(Icons.folder_outlined, color: theme.colorScheme.primary),
              ],
            ),
            const SizedBox(height: 16),
            Text(
              '${stats.totalFiles}',
              style: theme.textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 4),
            Text(
              'AES-256 Encrypted & Replicated',
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildQuickActionsGrid(BuildContext context, bool isDesktop) {
    final actions = [
      _ActionItem('Upload File', 'Encrypt & stream chunk blocks', Icons.cloud_upload_outlined, Colors.indigo, () {
        CustomSnackbar.showSuccess(context, 'Upload workflow starting (Phase 4)');
      }),
      _ActionItem('Download Files', 'Fetch & decrypt chunks', Icons.cloud_download_outlined, Colors.teal, () {
        CustomSnackbar.showSuccess(context, 'Download workflow starting (Phase 5)');
      }),
      _ActionItem('Become Host', 'Share capacity as micro-server', Icons.storage_outlined, Colors.cyan, () {
        context.go('/host');
      }),
      _ActionItem('Settings', 'Coordinator URL & interval', Icons.settings_outlined, Colors.purple, () {
        CustomSnackbar.showSuccess(context, 'Settings UI (Phase 5)');
      }),
    ];

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
