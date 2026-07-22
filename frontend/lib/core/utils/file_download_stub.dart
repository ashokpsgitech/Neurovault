import 'dart:io';
import 'dart:typed_data';
import 'package:file_picker/file_picker.dart';

Future<String?> downloadOrSaveFile(String filename, Uint8List bytes) async {
  try {
    String? path = await FilePicker.platform.saveFile(
      dialogTitle: 'Save Decrypted File',
      fileName: filename,
      bytes: bytes,
    );

    if (path == null || path.isEmpty) {
      final userHeader = Platform.environment['USERPROFILE'] ?? Platform.environment['HOME'] ?? '.';
      final downloadsDir = Directory('$userHeader\\Downloads');
      if (!downloadsDir.existsSync()) {
        downloadsDir.createSync(recursive: true);
      }
      final file = File('${downloadsDir.path}\\$filename');
      await file.writeAsBytes(bytes);
      return file.path;
    }
    return path;
  } catch (e) {
    try {
      final userHeader = Platform.environment['USERPROFILE'] ?? Platform.environment['HOME'] ?? '.';
      final downloadsDir = Directory('$userHeader\\Downloads');
      if (!downloadsDir.existsSync()) {
        downloadsDir.createSync(recursive: true);
      }
      final file = File('${downloadsDir.path}\\$filename');
      await file.writeAsBytes(bytes);
      return file.path;
    } catch (_) {
      return null;
    }
  }
}
