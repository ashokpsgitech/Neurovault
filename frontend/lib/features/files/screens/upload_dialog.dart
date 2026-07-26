import 'dart:convert';
import 'dart:developer' as dev;
import 'dart:typed_data';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import '../../../widgets/debug_console_modal.dart';

/// Modal dialog for picking files, previewing 4MB chunking, and streaming encrypted uploads.
class UploadDialog extends StatefulWidget {
  final Future<void> Function(String filename, Uint8List bytes) onUpload;

  const UploadDialog({super.key, required this.onUpload});

  @override
  State<UploadDialog> createState() => _UploadDialogState();
}

class _UploadDialogState extends State<UploadDialog> {
  String? _filename;
  Uint8List? _fileBytes;
  bool _isUploading = false;
  String? _errorMessage;
  String _progressStatus = '';

  Future<void> _pickFile() async {
    try {
      final result = await FilePicker.platform.pickFiles(withData: true);
      if (result != null && result.files.isNotEmpty) {
        final file = result.files.first;
        setState(() {
          _filename = file.name;
          _fileBytes = file.bytes;
          _errorMessage = null;
        });
      }
    } catch (e) {
      dev.log('[UploadDialog] FilePicker error: $e');
      _createSampleFile();
    }
  }

  void _createSampleFile() {
    final sampleContent = utf8.encode('NeuroVault Encrypted Document Payload - ${DateTime.now()}');
    setState(() {
      _filename = 'neurovault_sample_${DateTime.now().millisecondsSinceEpoch}.txt';
      _fileBytes = Uint8List.fromList(sampleContent);
      _errorMessage = null;
    });
  }

  String _formatBytes(int bytes) {
    if (bytes <= 0) return '0 B';
    const suffixes = ['B', 'KB', 'MB', 'GB'];
    int i = 0;
    double count = bytes.toDouble();
    while (count >= 1024 && i < suffixes.length - 1) {
      count /= 1024;
      i++;
    }
    return '${count.toStringAsFixed(1)} ${suffixes[i]}';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final size = _fileBytes?.length ?? 0;
    final chunkCount = size > 0 ? (size / (4 * 1024 * 1024)).ceil() : 0;

    return AlertDialog(
      title: Row(
        children: [
          const Icon(Icons.cloud_upload_outlined),
          const SizedBox(width: 12),
          const Expanded(child: Text('Upload File to Vault')),
          IconButton(
            icon: const Icon(Icons.bug_report_outlined, color: Colors.orange),
            tooltip: 'View In-App Debug Logs',
            onPressed: () => DebugConsoleModal.show(context),
          ),
        ],
      ),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Files are split into 4MB chunks and encrypted with AES-256-GCM before leaving your device.',
              style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
            const SizedBox(height: 20),

            // Error Message Card (inline, always visible on failure)
            if (_errorMessage != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: theme.colorScheme.errorContainer.withOpacity(0.8),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: theme.colorScheme.error.withOpacity(0.5)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Icon(Icons.error_outline, color: theme.colorScheme.error, size: 20),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'Upload Failed',
                                style: TextStyle(
                                  fontWeight: FontWeight.bold,
                                  color: theme.colorScheme.error,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Text(
                                _errorMessage!,
                                style: theme.textTheme.bodySmall?.copyWith(
                                  color: theme.colorScheme.onErrorContainer,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    OutlinedButton.icon(
                      icon: const Icon(Icons.bug_report_outlined, size: 16),
                      label: const Text('Inspect Full In-App Debug Logs'),
                      style: OutlinedButton.styleFrom(
                        visualDensity: VisualDensity.compact,
                        foregroundColor: theme.colorScheme.error,
                        side: BorderSide(color: theme.colorScheme.error.withOpacity(0.5)),
                      ),
                      onPressed: () => DebugConsoleModal.show(context),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
            ],

            // Progress status while uploading
            if (_isUploading && _progressStatus.isNotEmpty) ...[
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: theme.colorScheme.primaryContainer.withOpacity(0.5),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Row(
                  children: [
                    const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        _progressStatus,
                        style: theme.textTheme.bodySmall?.copyWith(fontWeight: FontWeight.bold),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 12),
            ],

            if (_filename == null) ...[
              OutlinedButton.icon(
                icon: const Icon(Icons.folder_open_outlined),
                label: const Text('Browse Device File'),
                style: OutlinedButton.styleFrom(
                  minimumSize: const Size(double.infinity, 48),
                ),
                onPressed: _pickFile,
              ),
              const SizedBox(height: 12),
              Center(
                child: TextButton.icon(
                  icon: const Icon(Icons.note_add_outlined),
                  label: const Text('Or Create Sample Vault Payload'),
                  onPressed: _createSampleFile,
                ),
              ),
            ] else ...[
              Card(
                color: theme.colorScheme.primary.withOpacity(0.08),
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Icon(Icons.description_outlined, color: theme.colorScheme.primary),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Text(
                              _filename!,
                              style: const TextStyle(fontWeight: FontWeight.bold),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text('File Size: ${_formatBytes(size)}'),
                          Text('4MB Chunks: $chunkCount block(s)'),
                        ],
                      ),
                      const SizedBox(height: 8),
                      const Row(
                        children: [
                          Icon(Icons.lock_outlined, size: 16, color: Colors.green),
                          SizedBox(width: 6),
                          Text('AES-256-GCM Encryption Ready', style: TextStyle(fontSize: 12, color: Colors.green)),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
              TextButton(
                onPressed: _isUploading
                    ? null
                    : () {
                        setState(() {
                          _filename = null;
                          _fileBytes = null;
                          _errorMessage = null;
                          _progressStatus = '';
                        });
                      },
                child: const Text('Choose Different File'),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _isUploading ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        ElevatedButton.icon(
          icon: _isUploading
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.shield_outlined),
          label: Text(_isUploading ? 'Encrypting & Streaming...' : 'Encrypt & Upload'),
          onPressed: (_fileBytes == null || _isUploading)
              ? null
              : () async {
                  setState(() {
                    _isUploading = true;
                    _errorMessage = null;
                    _progressStatus = 'Preparing...';
                  });
                  dev.log('[UploadDialog] Starting upload: $_filename (${_fileBytes!.length} bytes)');
                  final nav = Navigator.of(context);
                  final messenger = ScaffoldMessenger.of(context);
                  try {
                    await widget.onUpload(_filename!, _fileBytes!);
                    dev.log('[UploadDialog] Upload completed successfully');
                    if (!mounted) return;
                    nav.pop();
                    messenger.showSnackBar(
                      const SnackBar(
                        content: Text('File encrypted & uploaded to Vault!'),
                        backgroundColor: Colors.green,
                      ),
                    );
                  } catch (e, st) {
                    dev.log('[UploadDialog] Upload FAILED: $e', error: e, stackTrace: st);
                    if (!mounted) return;
                    setState(() {
                      _isUploading = false;
                      _progressStatus = '';
                      _errorMessage = e.toString().replaceFirst('Exception: ', '');
                    });
                  }
                },
        ),
      ],
    );
  }
}
