import 'dart:io';
import 'dart:typed_data';

/// Saves [bytes] as [filename] to [customDir] if provided, otherwise to the
/// platform's default Downloads folder. Returns the saved file path or null.
Future<String?> downloadOrSaveFile(String filename, Uint8List bytes, {String? customDir}) async {
  try {
    Directory? targetDir;

    if (customDir != null && customDir.isNotEmpty) {
      targetDir = Directory(customDir);
    } else if (Platform.isAndroid) {
      final downloadDir = Directory('/storage/emulated/0/Download');
      targetDir = downloadDir.existsSync() ? downloadDir : Directory.systemTemp;
    } else if (Platform.isWindows) {
      final userHeader = Platform.environment['USERPROFILE'] ?? '.';
      targetDir = Directory('$userHeader\\Downloads');
    } else {
      final home = Platform.environment['HOME'] ?? '.';
      targetDir = Directory('$home/Downloads');
    }

    if (!targetDir.existsSync()) {
      targetDir.createSync(recursive: true);
    }
    final targetFile = File('${targetDir.path}/$filename');
    await targetFile.writeAsBytes(bytes, flush: true);
    return targetFile.path;
  } catch (e) {
    try {
      final targetFile = File('${Directory.systemTemp.path}/$filename');
      await targetFile.writeAsBytes(bytes, flush: true);
      return targetFile.path;
    } catch (_) {
      return null;
    }
  }
}
