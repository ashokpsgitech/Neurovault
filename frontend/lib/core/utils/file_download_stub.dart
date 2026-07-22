import 'dart:io';
import 'dart:typed_data';
import 'package:file_picker/file_picker.dart';

Future<String?> downloadOrSaveFile(String filename, Uint8List bytes) async {
  try {
    final userHeader = Platform.environment['USERPROFILE'] ?? Platform.environment['HOME'] ?? '.';
    final downloadsDir = Directory('$userHeader\\Downloads');
    if (!downloadsDir.existsSync()) {
      downloadsDir.createSync(recursive: true);
    }
    final targetFile = File('${downloadsDir.path}\\$filename');
    await targetFile.writeAsBytes(bytes);

    try {
      final pickerPath = await FilePicker.platform.saveFile(
        dialogTitle: 'Save Decrypted File',
        fileName: filename,
        bytes: bytes,
      );
      if (pickerPath != null && pickerPath.isNotEmpty) {
        return pickerPath;
      }
    } catch (_) {}

    return targetFile.path;
  } catch (e) {
    return null;
  }
}
