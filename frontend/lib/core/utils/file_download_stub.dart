import 'dart:io';
import 'dart:typed_data';

Future<String?> downloadOrSaveFile(String filename, Uint8List bytes) async {
  try {
    Directory? targetDir;
    if (Platform.isAndroid) {
      final downloadDir = Directory('/storage/emulated/0/Download');
      if (downloadDir.existsSync()) {
        targetDir = downloadDir;
      } else {
        targetDir = Directory.systemTemp;
      }
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
