import 'dart:io';
import 'dart:typed_data';
import 'package:permission_handler/permission_handler.dart';

/// Saves [bytes] as [filename] to [customDir] if provided, otherwise to the
/// platform's default Downloads folder.
/// Automatically handles filename collisions with filename(1).ext, filename(2).ext, etc.
Future<String?> downloadOrSaveFile(String filename, Uint8List bytes, {String? customDir}) async {
  // Request storage permissions on Android if writing to external storage
  if (Platform.isAndroid) {
    try {
      final status = await Permission.storage.request();
      if (!status.isGranted) {
        await Permission.manageExternalStorage.request();
      }
    } catch (_) {}
  }

  Directory targetDir;

  if (customDir != null && customDir.trim().isNotEmpty) {
    targetDir = Directory(customDir.trim());
  } else if (Platform.isAndroid) {
    targetDir = Directory('/storage/emulated/0/Download');
  } else if (Platform.isWindows) {
    final userProfile = Platform.environment['USERPROFILE'] ?? 'C:';
    targetDir = Directory('$userProfile\\Downloads');
  } else {
    final home = Platform.environment['HOME'] ?? '.';
    targetDir = Directory('$home/Downloads');
  }

  if (!targetDir.existsSync()) {
    try {
      targetDir.createSync(recursive: true);
    } catch (_) {}
  }

  // Deduplicate filename if a file with the same name already exists in target directory
  final targetFile = _getDeduplicatedFile(targetDir, filename);

  try {
    await targetFile.writeAsBytes(bytes, flush: true);
    return targetFile.path;
  } catch (e) {
    // Primary custom location write error fallback
    try {
      final fallbackDir = Directory('/storage/emulated/0/Download');
      if (!fallbackDir.existsSync()) {
        fallbackDir.createSync(recursive: true);
      }
      final fallbackFile = _getDeduplicatedFile(fallbackDir, filename);
      await fallbackFile.writeAsBytes(bytes, flush: true);
      return fallbackFile.path;
    } catch (_) {
      return null;
    }
  }
}

/// Generates a unique non-colliding File instance in [dir] for [filename].
/// Format: filename.ext -> filename(1).ext -> filename(2).ext
File _getDeduplicatedFile(Directory dir, String filename) {
  final baseFile = File('${dir.path}/$filename');
  if (!baseFile.existsSync()) return baseFile;

  final lastDot = filename.lastIndexOf('.');
  String nameWithoutExt = filename;
  String ext = '';
  if (lastDot > 0) {
    nameWithoutExt = filename.substring(0, lastDot);
    ext = filename.substring(lastDot);
  }

  int count = 1;
  while (true) {
    final candidate = File('${dir.path}/$nameWithoutExt($count)$ext');
    if (!candidate.existsSync()) {
      return candidate;
    }
    count++;
  }
}
