/// Data model for files stored in NeuroVault.
class FileItem {
  final String id;
  final String filename;
  final int sizeBytes;
  final String contentType;
  final DateTime createdAt;
  final int chunkCount;

  const FileItem({
    required this.id,
    required this.filename,
    required this.sizeBytes,
    this.contentType = 'application/octet-stream',
    required this.createdAt,
    this.chunkCount = 1,
  });

  factory FileItem.fromJson(Map<String, dynamic> json) {
    return FileItem(
      id: json['id']?.toString() ?? '',
      filename: json['filename']?.toString() ?? 'file.bin',
      sizeBytes: json['sizeBytes'] ?? json['size'] ?? 0,
      contentType: json['contentType']?.toString() ?? 'application/octet-stream',
      createdAt: json['createdAt'] != null
          ? DateTime.parse(json['createdAt'].toString())
          : DateTime.now(),
      chunkCount: json['chunkCount'] ?? 1,
    );
  }
}

/// DTO response for POST /api/files/upload-plan
class UploadPlanResponse {
  final String sessionId;
  final String fileId;
  final List<ChunkAllocation> chunkAllocations;

  const UploadPlanResponse({
    required this.sessionId,
    required this.fileId,
    required this.chunkAllocations,
  });

  factory UploadPlanResponse.fromJson(Map<String, dynamic> json) {
    var rawList = json['chunkAllocations'] as List? ?? [];
    List<ChunkAllocation> allocations =
        rawList.map((i) => ChunkAllocation.fromJson(i)).toList();

    return UploadPlanResponse(
      sessionId: json['sessionId']?.toString() ?? '',
      fileId: json['fileId']?.toString() ?? '',
      chunkAllocations: allocations,
    );
  }
}

class ChunkAllocation {
  final String chunkId;
  final int chunkIndex;
  final String primaryHostUrl;

  const ChunkAllocation({
    required this.chunkId,
    required this.chunkIndex,
    required this.primaryHostUrl,
  });

  factory ChunkAllocation.fromJson(Map<String, dynamic> json) {
    return ChunkAllocation(
      chunkId: json['chunkId']?.toString() ?? '',
      chunkIndex: json['chunkIndex'] ?? 0,
      primaryHostUrl: json['primaryHostUrl']?.toString() ?? 'http://localhost:8080',
    );
  }
}

/// DTO response for GET /api/files/download-plan/{fileId}
class DownloadPlanResponse {
  final String fileId;
  final String filename;
  final int sizeBytes;
  final List<ChunkLocation> chunkLocations;

  const DownloadPlanResponse({
    required this.fileId,
    required this.filename,
    required this.sizeBytes,
    required this.chunkLocations,
  });

  factory DownloadPlanResponse.fromJson(Map<String, dynamic> json) {
    var rawList = json['chunkLocations'] as List? ?? [];
    List<ChunkLocation> locations =
        rawList.map((i) => ChunkLocation.fromJson(i)).toList();

    return DownloadPlanResponse(
      fileId: json['fileId']?.toString() ?? '',
      filename: json['filename']?.toString() ?? 'download.bin',
      sizeBytes: json['sizeBytes'] ?? 0,
      chunkLocations: locations,
    );
  }
}

class ChunkLocation {
  final String chunkId;
  final int chunkIndex;
  final String hostUrl;

  const ChunkLocation({
    required this.chunkId,
    required this.chunkIndex,
    required this.hostUrl,
  });

  factory ChunkLocation.fromJson(Map<String, dynamic> json) {
    return ChunkLocation(
      chunkId: json['chunkId']?.toString() ?? '',
      chunkIndex: json['chunkIndex'] ?? 0,
      hostUrl: json['hostUrl']?.toString() ?? 'http://localhost:8080',
    );
  }
}
