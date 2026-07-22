import 'dart:typed_data';
import 'package:file_picker/file_picker.dart';

Future<String?> downloadOrSaveFile(String filename, Uint8List bytes) async {
  try {
    final result = await FilePicker.platform.saveFile(
      dialogTitle: 'Save Decrypted File',
      fileName: filename,
      bytes: bytes,
    );
    return result;
  } catch (e) {
    return null;
  }
}
