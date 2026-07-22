import 'dart:io';
import 'dart:typed_data';

Future<String?> downloadOrSaveFile(String filename, Uint8List bytes) async {
  try {
    final userHeader = Platform.environment['USERPROFILE'] ?? Platform.environment['HOME'] ?? '.';
    final downloadsDir = Directory('$userHeader\\Downloads');
    if (!downloadsDir.existsSync()) {
      downloadsDir.createSync(recursive: true);
    }
    final targetFile = File('${downloadsDir.path}\\$filename');
    await targetFile.writeAsBytes(bytes, flush: true);
    return targetFile.path;
  } catch (e) {
    try {
      final currentDir = Directory.current;
      final targetFile = File('${currentDir.path}\\$filename');
      await targetFile.writeAsBytes(bytes, flush: true);
      return targetFile.path;
    } catch (_) {
      return null;
    }
  }
}
