/// Progress tracking model for upload and download streaming operations.
class PipelineProgress {
  final String filename;
  final int currentChunk;
  final int totalChunks;
  final double progressPercent; // 0.0 to 1.0
  final String status; // 'ENCRYPTING', 'UPLOADING', 'DOWNLOADING', 'DECRYPTING', 'COMPLETE'

  const PipelineProgress({
    required this.filename,
    this.currentChunk = 0,
    this.totalChunks = 1,
    this.progressPercent = 0.0,
    this.status = 'UPLOADING',
  });
}
