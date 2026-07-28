import 'dart:typed_data';

import 'file_download_stub.dart'
    if (dart.library.html) 'file_download_web.dart';

/// Cross-platform helper to download or save decrypted files to disk.
/// Optionally accepts [customDir] to override the default Downloads directory.
Future<String?> saveDecryptedFileToDisk(String filename, Uint8List bytes, {String? customDir}) {
  return downloadOrSaveFile(filename, bytes, customDir: customDir);
}
