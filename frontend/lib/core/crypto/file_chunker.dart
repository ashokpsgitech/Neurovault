import 'dart:typed_data';

/// Dynamic Chunking Engine for splitting and reassembling files based on active host nodes.
class FileChunker {
  static const int defaultChunkSize = 4 * 1024 * 1024; // 4MB default fallback

  /// Splits raw file bytes into dynamic chunk payload blocks based on the number of active host nodes.
  /// If [activeHostCount] > 1, splits the payload into [activeHostCount] dynamic chunks
  /// so chunks are dynamically distributed and replicated across available container nodes.
  static List<Uint8List> splitIntoChunks(Uint8List fileBytes, {int? activeHostCount}) {
    if (fileBytes.isEmpty) return [Uint8List(0)];

    int targetChunkSize = defaultChunkSize;

    if (activeHostCount != null && activeHostCount > 1) {
      targetChunkSize = (fileBytes.length / activeHostCount).ceil();
      if (targetChunkSize < 64 * 1024) {
        targetChunkSize = 64 * 1024; // Minimum 64 KB per chunk block
      }
    }

    final List<Uint8List> chunks = [];
    int offset = 0;

    while (offset < fileBytes.length) {
      int end = offset + targetChunkSize;
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
