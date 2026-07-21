import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../providers/core_providers.dart';
import '../../../widgets/custom_snackbar.dart';

/// App Settings Screen for NeuroVault.
class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  final TextEditingController _apiUrlController = TextEditingController(text: 'http://localhost:8080');
  final TextEditingController _storageDirController = TextEditingController(text: './neurovault-storage');

  @override
  void dispose() {
    _apiUrlController.dispose();
    _storageDirController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isDark = ref.watch(themeModeProvider) == ThemeMode.dark;

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.go('/dashboard'),
        ),
        title: const Text('Application Settings', style: TextStyle(fontWeight: FontWeight.bold)),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24.0),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 800),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Coordinator Network Settings
                Text('Coordinator Network', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                const SizedBox(height: 12),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(20.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        TextField(
                          controller: _apiUrlController,
                          decoration: const InputDecoration(
                            labelText: 'Spring Boot Coordinator API URL',
                            prefixIcon: Icon(Icons.dns_outlined),
                            border: OutlineInputBorder(),
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'REST Coordinator managing metadata, replication, and telemetry.',
                          style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 24),

                // Encryption Engine Parameters
                Text('Encryption & Engine Parameters', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                const SizedBox(height: 12),
                Card(
                  child: Column(
                    children: const [
                      ListTile(
                        leading: Icon(Icons.security_outlined),
                        title: Text('Cipher Suite'),
                        trailing: Text('AES-256-GCM + RSA-4096', style: TextStyle(fontWeight: FontWeight.bold)),
                      ),
                      Divider(height: 1),
                      ListTile(
                        leading: Icon(Icons.grid_4x4_outlined),
                        title: Text('Fixed Chunk Block Size'),
                        trailing: Text('4 MB (4,194,304 bytes)', style: TextStyle(fontWeight: FontWeight.bold)),
                      ),
                      Divider(height: 1),
                      ListTile(
                        leading: Icon(Icons.verified_outlined),
                        title: Text('Integrity Checksum'),
                        trailing: Text('SHA-256 Hex Hash', style: TextStyle(fontWeight: FontWeight.bold)),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 24),

                // App Theme Settings
                Text('Appearance', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                const SizedBox(height: 12),
                Card(
                  child: SwitchListTile(
                    secondary: Icon(isDark ? Icons.dark_mode_outlined : Icons.light_mode_outlined),
                    title: const Text('Dark Mode Theme'),
                    subtitle: const Text('Material 3 Cyber-Vault Dark Aesthetic'),
                    value: isDark,
                    onChanged: (val) {
                      ref.read(themeModeProvider.notifier).state =
                          val ? ThemeMode.dark : ThemeMode.light;
                    },
                  ),
                ),
                const SizedBox(height: 32),

                // Save Action
                SizedBox(
                  width: double.infinity,
                  height: 48,
                  child: ElevatedButton.icon(
                    icon: const Icon(Icons.save_outlined),
                    label: const Text('Save Settings'),
                    onPressed: () {
                      CustomSnackbar.showSuccess(context, 'Settings updated successfully');
                      context.go('/dashboard');
                    },
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
