import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../features/authentication/providers/auth_provider.dart';
import '../features/authentication/providers/auth_state.dart';
import 'custom_snackbar.dart';

/// Modal dialog presented to users upon first-time Google Sign-In
/// allowing them to explicitly select their primary role and operating mode.
class RoleSelectionDialog extends ConsumerStatefulWidget {
  final VoidCallback? onCompleted;

  const RoleSelectionDialog({super.key, this.onCompleted});

  static Future<void> showIfNeeded(BuildContext context, WidgetRef ref) async {
    final authState = ref.read(authStateProvider);
    if (authState is Authenticated) {
      final user = authState.user;
      if (user.role == 'UNSELECTED' || user.role.isEmpty) {
        await showDialog(
          context: context,
          barrierDismissible: false,
          builder: (ctx) => const RoleSelectionDialog(),
        );
      }
    }
  }

  @override
  ConsumerState<RoleSelectionDialog> createState() => _RoleSelectionDialogState();
}

class _RoleSelectionDialogState extends ConsumerState<RoleSelectionDialog> {
  String _selectedRole = 'CLIENT';
  String _selectedMode = 'PRIVATE';
  bool _isSaving = false;

  Future<void> _saveSelection() async {
    setState(() => _isSaving = true);
    try {
      await ref.read(authStateProvider.notifier).updateUserPreferences(
            role: _selectedRole,
            mode: _selectedMode,
          );
      if (mounted) {
        CustomSnackbar.showSuccess(context, 'Account role set to $_selectedRole ($_selectedMode mode)!');
        Navigator.of(context, rootNavigator: true).pop();
        widget.onCompleted?.call();
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isSaving = false);
        CustomSnackbar.showError(context, 'Failed to save role preferences: $e');
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return PopScope(
      canPop: false,
      child: AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
        title: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: theme.colorScheme.primary.withOpacity(0.12),
                shape: BoxShape.circle,
              ),
              child: Icon(Icons.person_pin_outlined, color: theme.colorScheme.primary, size: 28),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Welcome to NeuroVault!',
                    style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    'Choose your account role to finish Google setup',
                    style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                  ),
                ],
              ),
            ),
          ],
        ),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: 8),
              Text(
                '1. Primary Account Role',
                style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 6),
              Text(
                'Select how this device participates in the decentralized vault network.',
                style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
              const SizedBox(height: 12),

              // Role Cards
              _buildRoleTile(
                value: 'CLIENT',
                title: 'Client Node',
                subtitle: 'Encrypt & store files in zero-cloud storage vault',
                icon: Icons.cloud_upload_outlined,
                color: Colors.blue,
              ),
              const SizedBox(height: 8),
              _buildRoleTile(
                value: 'HOST',
                title: 'Host Micro-Server',
                subtitle: 'Provide disk container space to store & serve encrypted chunks 24/7',
                icon: Icons.storage_outlined,
                color: Colors.green,
              ),
              const SizedBox(height: 8),
              _buildRoleTile(
                value: 'BOTH',
                title: 'Both (Host + Client)',
                subtitle: 'Full mesh participation: Store vault files & run 24/7 micro-server host',
                icon: Icons.hub_outlined,
                color: Colors.purple,
              ),

              const SizedBox(height: 20),
              Text(
                '2. Operating Network Mode',
                style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              SegmentedButton<String>(
                segments: const [
                  ButtonSegment(
                    value: 'PRIVATE',
                    label: Text('Private Mesh'),
                    icon: Icon(Icons.lock_outline),
                  ),
                  ButtonSegment(
                    value: 'PUBLIC',
                    label: Text('Public Network'),
                    icon: Icon(Icons.public_outlined),
                  ),
                ],
                selected: {_selectedMode},
                onSelectionChanged: (selection) {
                  setState(() => _selectedMode = selection.first);
                },
              ),
            ],
          ),
        ),
        actions: [
          SizedBox(
            width: double.infinity,
            height: 48,
            child: FilledButton.icon(
              icon: _isSaving
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                    )
                  : const Icon(Icons.check_circle_outline),
              label: Text(_isSaving ? 'Saving Profile...' : 'Confirm Role & Continue'),
              style: FilledButton.styleFrom(
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              onPressed: _isSaving ? null : _saveSelection,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRoleTile({
    required String value,
    required String title,
    required String subtitle,
    required IconData icon,
    required Color color,
  }) {
    final isSelected = _selectedRole == value;
    final theme = Theme.of(context);

    return InkWell(
      onTap: () => setState(() => _selectedRole = value),
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: isSelected ? color.withOpacity(0.12) : theme.colorScheme.surfaceContainerHighest.withOpacity(0.4),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: isSelected ? color : theme.colorScheme.outlineVariant.withOpacity(0.5),
            width: isSelected ? 2 : 1,
          ),
        ),
        child: Row(
          children: [
            Icon(icon, color: isSelected ? color : Colors.grey, size: 28),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      fontSize: 14,
                      color: isSelected ? color : theme.colorScheme.onSurface,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: TextStyle(fontSize: 11, color: theme.colorScheme.onSurfaceVariant),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Icon(
              isSelected ? Icons.radio_button_checked : Icons.radio_button_off,
              color: isSelected ? color : Colors.grey,
              size: 20,
            ),
          ],
        ),
      ),
    );
  }
}
