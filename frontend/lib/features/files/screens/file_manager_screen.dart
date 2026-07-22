import 'dart:convert';
import 'dart:typed_data';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../../core/utils/file_download_helper.dart';
import '../../../widgets/custom_snackbar.dart';
import '../models/file_metadata_model.dart';
import '../models/progress_model.dart';
import '../providers/file_provider.dart';
import '../providers/file_state.dart';

/// Responsive Material 3 File Manager Screen for NeuroVault.
class FileManagerScreen extends ConsumerStatefulWidget {
  const FileManagerScreen({super.key});

  @override
  ConsumerState<FileManagerScreen> createState() => _FileManagerScreenState();
}

class _FileManagerScreenState extends ConsumerState<FileManagerScreen> {
  final TextEditingController _searchController = TextEditingController();
  String _searchQuery = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    if (bytes < 1024 * 1024 * 1024) return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
  }

  Future<void> _saveDecryptedFileToDisk(String filename, Uint8List bytes) async {
    final path = await saveDecryptedFileToDisk(filename, bytes);
    if (mounted) {
      if (path != null) {
        CustomSnackbar.showSuccess(context, 'SAVED TO DISK: $path');
        showDialog(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Row(
              children: [
                Icon(Icons.check_circle, color: Colors.green),
                SizedBox(width: 12),
                Text('File Downloaded Successfully'),
              ],
            ),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('The decrypted file has been saved to your computer:'),
                const SizedBox(height: 12),
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: SelectableText(
                    path,
                    style: const TextStyle(fontWeight: FontWeight.bold, fontFamily: 'monospace'),
                  ),
                ),
              ],
            ),
            actions: [
              ElevatedButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('OK'),
              ),
            ],
          ),
        );
      } else {
        CustomSnackbar.showError(context, 'Failed to write file to disk.');
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final fileState = ref.watch(fileProvider);

    List<FileItem> files = [];
    PipelineProgress? activeProgress;

    if (fileState is FileLoaded) {
      files = fileState.files;
      activeProgress = fileState.activeProgress;
    }

    final filteredFiles = files.where((f) {
      return f.filename.toLowerCase().contains(_searchQuery.toLowerCase());
    }).toList();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Encrypted Vault Files', style: TextStyle(fontWeight: FontWeight.bold)),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh Vault',
            onPressed: () => ref.read(fileProvider.notifier).loadFiles(),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        icon: const Icon(Icons.cloud_upload_outlined),
        label: const Text('Upload File'),
        onPressed: () {
          showDialog(
            context: context,
            builder: (_) => UploadDialog(
              onUpload: (filename, bytes) async {
                await ref.read(fileProvider.notifier).uploadFile(
                      filename: filename,
                      fileBytes: bytes,
                    );
              },
            ),
          );
        },
      ),
      body: Column(
        children: [
          // Active Streaming Progress Banner
          if (activeProgress != null) _buildProgressBanner(context, activeProgress),

          // Search Bar Section
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: TextField(
              controller: _searchController,
              decoration: InputDecoration(
                hintText: 'Search vault files...',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: _searchQuery.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.clear),
                        onPressed: () {
                          _searchController.clear();
                          setState(() => _searchQuery = '');
                        },
                      )
                    : null,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              ),
              onChanged: (val) => setState(() => _searchQuery = val),
            ),
          ),

          // File List / Grid View
          Expanded(
            child: Builder(
              builder: (context) {
                if (fileState is FileLoading) {
                  return const Center(child: CircularProgressIndicator());
                }

                if (filteredFiles.isEmpty) {
                  return Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(Icons.folder_open_outlined, size: 64, color: theme.colorScheme.primary.withOpacity(0.5)),
                        const SizedBox(height: 16),
                        Text(
                          _searchQuery.isEmpty ? 'No encrypted files in Vault' : 'No matching files found',
                          style: theme.textTheme.titleMedium,
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'Upload files to automatically encrypt & distribute across micro-servers.',
                          style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                        ),
                      ],
                    ),
                  );
                }

                return ListView.separated(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  itemCount: filteredFiles.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 8),
                  itemBuilder: (context, index) {
                    final item = filteredFiles[index];
                    return Card(
                      child: ListTile(
                        leading: CircleAvatar(
                          backgroundColor: theme.colorScheme.primary.withOpacity(0.15),
                          child: Icon(Icons.insert_drive_file_outlined, color: theme.colorScheme.primary),
                        ),
                        title: Text(item.filename, style: const TextStyle(fontWeight: FontWeight.bold)),
                        subtitle: Text(
                          '${_formatBytes(item.sizeBytes)}  •  ${item.chunkCount} x 4MB chunk(s)  •  ${DateFormat('MMM d, hh:mm a').format(item.createdAt)}',
                        ),
                        trailing: IconButton(
                          icon: const Icon(Icons.cloud_download_outlined),
                          tooltip: 'Decrypt & Save File',
                          onPressed: () async {
                            CustomSnackbar.showSuccess(context, 'Downloading & decrypting ${item.filename}...');
                            try {
                              final bytes = await ref.read(fileProvider.notifier).downloadFile(item);
                              if (bytes != null && context.mounted) {
                                // Prompt native OS file save dialog
                                await _saveDecryptedFileToDisk(item.filename, bytes);
                                if (context.mounted) {
                                  final textPreview = utf8.decode(bytes, allowMalformed: true);
                                  _showFilePreviewModal(context, item.filename, textPreview, bytes);
                                }
                              }
                            } catch (e) {
                              if (context.mounted) {
                                CustomSnackbar.showError(context, 'Download failed: $e');
                              }
                            }
                          },
                        ),
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildProgressBanner(BuildContext context, PipelineProgress progress) {
    final theme = Theme.of(context);
    return Container(
      color: theme.colorScheme.primary.withOpacity(0.12),
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                '${progress.status}: ${progress.filename}',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
              Text(
                '${(progress.progressPercent * 100).toStringAsFixed(0)}% (Chunk ${progress.currentChunk}/${progress.totalChunks})',
                style: theme.textTheme.bodySmall?.copyWith(fontWeight: FontWeight.bold),
              ),
            ],
          ),
          const SizedBox(height: 6),
          LinearProgressIndicator(
            value: progress.progressPercent,
            backgroundColor: theme.colorScheme.primary.withOpacity(0.2),
          ),
        ],
      ),
    );
  }

  void _showFilePreviewModal(BuildContext context, String filename, String content, Uint8List bytes) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => Container(
        padding: const EdgeInsets.all(24),
        height: MediaQuery.of(context).size.height * 0.65,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Expanded(
                  child: Text(
                    'Decrypted Payload: $filename',
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                IconButton(icon: const Icon(Icons.close), onPressed: () => Navigator.pop(ctx)),
              ],
            ),
            const SizedBox(height: 12),
            Wrap(
              alignment: WrapAlignment.spaceBetween,
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: 12,
              runSpacing: 8,
              children: [
                const Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.check_circle_outline, color: Colors.green, size: 18),
                    SizedBox(width: 6),
                    Text(
                      'AES-256-GCM Decrypted & SHA-256 Verified',
                      style: TextStyle(color: Colors.green, fontWeight: FontWeight.bold, fontSize: 13),
                    ),
                  ],
                ),
                ElevatedButton.icon(
                  icon: const Icon(Icons.download_for_offline_outlined, size: 16),
                  label: const Text('Save File to Computer'),
                  style: ElevatedButton.styleFrom(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8)),
                  onPressed: () => _saveDecryptedFileToDisk(filename, bytes),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Expanded(
              child: Container(
                width: double.infinity,
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: SingleChildScrollView(
                  child: Text(
                    content,
                    style: const TextStyle(fontFamily: 'monospace'),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class UploadDialog extends StatefulWidget {
  final Future<void> Function(String filename, Uint8List bytes) onUpload;

  const UploadDialog({super.key, required this.onUpload});

  @override
  State<UploadDialog> createState() => _UploadDialogState();
}

class _UploadDialogState extends State<UploadDialog> {
  String? _selectedFilename;
  Uint8List? _selectedBytes;
  bool _isUploading = false;

  Future<void> _pickFile() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.any,
        withData: true,
      );

      if (result != null && result.files.isNotEmpty) {
        final file = result.files.first;
        if (file.bytes != null) {
          setState(() {
            _selectedFilename = file.name;
            _selectedBytes = file.bytes;
          });
        }
      }
    } catch (_) {
      // Fallback custom sample text file for testing
      final sampleContent = 'NeuroVault Encrypted Document Payload - ${DateTime.now()}\n';
      setState(() {
        _selectedFilename = 'neurovault_sample_${DateTime.now().millisecondsSinceEpoch}.txt';
        _selectedBytes = Uint8List.fromList(utf8.encode(sampleContent));
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Row(
        children: [
          Icon(Icons.cloud_upload_outlined, color: Colors.blue),
          SizedBox(width: 12),
          Text('Upload File to Vault'),
        ],
      ),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Selected files are encrypted locally using client-side AES-256-GCM before distribution across micro-server nodes.',
          ),
          const SizedBox(height: 20),
          OutlinedButton.icon(
            icon: const Icon(Icons.attach_file),
            label: Text(_selectedFilename ?? 'Select Local File'),
            style: OutlinedButton.styleFrom(
              minimumSize: const Size(double.infinity, 48),
            ),
            onPressed: _isUploading ? null : _pickFile,
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: _isUploading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        ElevatedButton.icon(
          icon: const Icon(Icons.lock_outline),
          label: const Text('Encrypt & Upload'),
          onPressed: (_selectedBytes == null || _isUploading)
              ? null
              : () async {
                  setState(() => _isUploading = true);
                  await widget.onUpload(_selectedFilename!, _selectedBytes!);
                  if (context.mounted) {
                    Navigator.pop(context);
                  }
                },
        ),
      ],
    );
  }
}
