import 'dart:typed_data';

/// 4MB (4,194,304 bytes) Chunking Engine for splitting and reassembling files.
class FileChunker {
  static const int chunkSize = 4 * 1024 * 1024; // 4MB

  /// Splits raw file bytes into 4MB chunk payload blocks.
  static List<Uint8List> splitIntoChunks(Uint8List fileBytes) {
    final List<Uint8List> chunks = [];
    int offset = 0;

    while (offset < fileBytes.length) {
      int end = offset + chunkSize;
      if (end > fileBytes.length) {
        end = fileBytes.length;
      }
      chunks.add(fileBytes.sublist(offset, end));
      offset = end;
    }

    return chunks;
  }

  /// Reassembles downloaded chunk blocks into a single contiguous byte array.
  static Uint8List reassembleChunks(List<Uint8List> chunks) {
    final totalLength = chunks.fold<int>(0, (sum, chunk) => sum + chunk.length);
    final result = Uint8List(totalLength);
    int offset = 0;

    for (final chunk in chunks) {
      result.setRange(offset, offset + chunk.length, chunk);
      offset += chunk.length;
    }

    return result;
  }
}
