import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../core/utils/debug_log_service.dart';
import 'custom_snackbar.dart';

/// In-App Debug Console Bottom Sheet / Dialog for mobile & desktop error inspection.
class DebugConsoleModal extends StatefulWidget {
  const DebugConsoleModal({super.key});

  static void show(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => const DebugConsoleModal(),
    );
  }

  @override
  State<DebugConsoleModal> createState() => _DebugConsoleModalState();
}

class _DebugConsoleModalState extends State<DebugConsoleModal> {
  final DebugLogService _logService = DebugLogService();
  bool _filterErrorsOnly = false;

  @override
  void initState() {
    super.initState();
    _logService.addListener(_onLogUpdated);
  }

  @override
  void dispose() {
    _logService.removeListener(_onLogUpdated);
    super.dispose();
  }

  void _onLogUpdated() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final allLogs = _logService.logs;
    final displayLogs = _filterErrorsOnly
        ? allLogs.where((l) => l.level == 'ERROR').toList()
        : allLogs;

    return Container(
      height: MediaQuery.of(context).size.height * 0.85,
      decoration: BoxDecoration(
        color: theme.colorScheme.surface,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
      ),
      child: Column(
        children: [
          // Drag Handle
          const SizedBox(height: 12),
          Container(
            width: 40,
            height: 4,
            decoration: BoxDecoration(
              color: theme.colorScheme.onSurfaceVariant.withOpacity(0.4),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(height: 12),

          // Header Bar
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Row(
              children: [
                const Icon(Icons.bug_report_outlined, color: Colors.orange),
                const SizedBox(width: 10),
                Text(
                  'In-App Debug Logs',
                  style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                ),
                const Spacer(),
                FilterChip(
                  selected: _filterErrorsOnly,
                  label: const Text('Errors Only', style: TextStyle(fontSize: 12)),
                  onSelected: (val) => setState(() => _filterErrorsOnly = val),
                ),
                const SizedBox(width: 8),
                IconButton(
                  icon: const Icon(Icons.copy_all_outlined),
                  tooltip: 'Copy Logs',
                  onPressed: () {
                    final text = _logService.exportLogsText();
                    Clipboard.setData(ClipboardData(text: text));
                    CustomSnackbar.showSuccess(context, 'Logs copied to clipboard!');
                  },
                ),
                IconButton(
                  icon: const Icon(Icons.delete_outline),
                  tooltip: 'Clear Logs',
                  onPressed: () {
                    _logService.clear();
                  },
                ),
              ],
            ),
          ),
          const Divider(height: 1),

          // Log List
          Expanded(
            child: displayLogs.isEmpty
                ? Center(
                    child: Text(
                      'No logs recorded yet.',
                      style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                    ),
                  )
                : ListView.separated(
                    padding: const EdgeInsets.all(16),
                    itemCount: displayLogs.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 8),
                    itemBuilder: (context, index) {
                      final item = displayLogs[index];
                      final isError = item.level == 'ERROR';
                      final isWarn = item.level == 'WARN';

                      Color cardColor = theme.colorScheme.surfaceContainerHighest.withOpacity(0.5);
                      Color textColor = theme.colorScheme.onSurface;
                      if (isError) {
                        cardColor = Colors.red.withOpacity(0.12);
                        textColor = Colors.red.shade700;
                      } else if (isWarn) {
                        cardColor = Colors.orange.withOpacity(0.12);
                        textColor = Colors.orange.shade800;
                      }

                      return Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: cardColor,
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(
                            color: isError ? Colors.red.withOpacity(0.3) : Colors.transparent,
                          ),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Text(
                                  '[${item.timeFormatted}]',
                                  style: TextStyle(
                                    fontSize: 11,
                                    fontFamily: 'monospace',
                                    fontWeight: FontWeight.bold,
                                    color: theme.colorScheme.onSurfaceVariant,
                                  ),
                                ),
                                const SizedBox(width: 8),
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                  decoration: BoxDecoration(
                                    color: isError ? Colors.red : (isWarn ? Colors.orange : theme.colorScheme.primary),
                                    borderRadius: BorderRadius.circular(4),
                                  ),
                                  child: Text(
                                    item.level,
                                    style: const TextStyle(fontSize: 10, color: Colors.white, fontWeight: FontWeight.bold),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 6),
                            SelectableText(
                              item.message,
                              style: TextStyle(fontSize: 13, color: textColor, fontWeight: isError ? FontWeight.bold : FontWeight.normal),
                            ),
                            if (item.error != null) ...[
                              const SizedBox(height: 6),
                              SelectableText(
                                'Error: ${item.error}',
                                style: const TextStyle(fontSize: 12, color: Colors.red, fontFamily: 'monospace'),
                              ),
                            ],
                            if (item.stackTrace != null) ...[
                              const SizedBox(height: 4),
                              SelectableText(
                                item.stackTrace!,
                                style: TextStyle(fontSize: 10, color: theme.colorScheme.onSurfaceVariant, fontFamily: 'monospace'),
                              ),
                            ],
                          ],
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
