import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../widgets/custom_snackbar.dart';
import '../models/file_metadata_model.dart';
import '../models/progress_model.dart';
import '../providers/file_provider.dart';
import '../providers/file_state.dart';
import 'upload_dialog.dart';

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
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.go('/dashboard'),
        ),
        title: const Text('Encrypted Vault Files', style: TextStyle(fontWeight: FontWeight.bold)),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh_outlined),
            onPressed: () => ref.read(fileProvider.notifier).loadFiles(),
          ),
          const SizedBox(width: 8),
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
                          tooltip: 'Decrypt & Download',
                          onPressed: () async {
                            CustomSnackbar.showSuccess(context, 'Downloading & decrypting ${item.filename}...');
                            final bytes = await ref.read(fileProvider.notifier).downloadFile(item);
                            if (bytes != null && context.mounted) {
                              final textPreview = utf8.decode(bytes, allowMalformed: true);
                              _showFilePreviewModal(context, item.filename, textPreview);
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

  void _showFilePreviewModal(BuildContext context, String filename, String content) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => Container(
        padding: const EdgeInsets.all(24),
        height: MediaQuery.of(context).size.height * 0.6,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'Decrypted Payload: $filename',
                  style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                ),
                IconButton(icon: const Icon(Icons.close), onPressed: () => Navigator.pop(ctx)),
              ],
            ),
            const SizedBox(height: 12),
            const Row(
              children: [
                Icon(Icons.check_circle_outline, color: Colors.green, size: 18),
                SizedBox(width: 6),
                Text('AES-256-GCM Decrypted & SHA-256 Verified', style: TextStyle(color: Colors.green, fontWeight: FontWeight.bold)),
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
