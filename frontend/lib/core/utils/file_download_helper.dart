import 'dart:typed_data';

import 'file_download_stub.dart'
    if (dart.library.html) 'file_download_web.dart';

/// Cross-platform helper to download or save decrypted files to disk.
Future<String?> saveDecryptedFileToDisk(String filename, Uint8List bytes) {
  return downloadOrSaveFile(filename, bytes);
}
