import 'dart:convert';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';

/// Metadata for a sibling chunk within the same file cluster.
class SiblingChunkInfo {
  final int chunkIndex;
  final String chunkId;
  final int sizeBytes;
  final String sha256;
  final String assignedHostId;
  final String assignedHostname;
  final String assignedHostDevice;
  final String assignedHostIp;
  final List<String> replicaHostIds;

  SiblingChunkInfo({
    required this.chunkIndex,
    required this.chunkId,
    required this.sizeBytes,
    required this.sha256,
    required this.assignedHostId,
    this.assignedHostname = '',
    this.assignedHostDevice = '',
    this.assignedHostIp = '',
    this.replicaHostIds = const [],
  });

  Map<String, dynamic> toJson() => {
    'chunkIndex': chunkIndex,
    'chunkId': chunkId,
    'sizeBytes': sizeBytes,
    'sha256': sha256,
    'assignedHostId': assignedHostId,
    'assignedHostname': assignedHostname,
    'assignedHostDevice': assignedHostDevice,
    'assignedHostIp': assignedHostIp,
    'replicaHostIds': replicaHostIds,
  };

  factory SiblingChunkInfo.fromJson(Map<String, dynamic> json) => SiblingChunkInfo(
    chunkIndex: json['chunkIndex'] as int? ?? 0,
    chunkId: json['chunkId']?.toString() ?? '',
    sizeBytes: json['sizeBytes'] as int? ?? 0,
    sha256: json['sha256']?.toString() ?? '',
    assignedHostId: json['assignedHostId']?.toString() ?? '',
    assignedHostname: json['assignedHostname']?.toString() ?? '',
    assignedHostDevice: json['assignedHostDevice']?.toString() ?? '',
    assignedHostIp: json['assignedHostIp']?.toString() ?? '',
    replicaHostIds: (json['replicaHostIds'] as List<dynamic>?)?.map((e) => e.toString()).toList() ?? [],
  );
}

/// Cluster-wide Manifest embedded into each chunk envelope.
/// Allows any storage host to understand the complete topology of the parent file
/// and coordinate replication / self-healing repairs autonomously.
class ChunkManifest {
  static const int currentVersion = 1;

  final int version;
  final String fileId;
  final String filename;
  final int fileSizeBytes;
  final int totalChunks;
  final int currentChunkIndex;
  final String currentChunkId;
  final String createdAt;
  final List<SiblingChunkInfo> siblingChunks;

  ChunkManifest({
    this.version = currentVersion,
    required this.fileId,
    required this.filename,
    required this.fileSizeBytes,
    required this.totalChunks,
    required this.currentChunkIndex,
    required this.currentChunkId,
    String? createdAt,
    required this.siblingChunks,
  }) : createdAt = createdAt ?? DateTime.now().toIso8601String();

  Map<String, dynamic> toJson() => {
    'version': version,
    'fileId': fileId,
    'filename': filename,
    'fileSizeBytes': fileSizeBytes,
    'totalChunks': totalChunks,
    'currentChunkIndex': currentChunkIndex,
    'currentChunkId': currentChunkId,
    'createdAt': createdAt,
    'siblingChunks': siblingChunks.map((s) => s.toJson()).toList(),
  };

  factory ChunkManifest.fromJson(Map<String, dynamic> json) => ChunkManifest(
    version: json['version'] as int? ?? currentVersion,
    fileId: json['fileId']?.toString() ?? '',
    filename: json['filename']?.toString() ?? '',
    fileSizeBytes: json['fileSizeBytes'] as int? ?? 0,
    totalChunks: json['totalChunks'] as int? ?? 1,
    currentChunkIndex: json['currentChunkIndex'] as int? ?? 0,
    currentChunkId: json['currentChunkId']?.toString() ?? '',
    createdAt: json['createdAt']?.toString(),
    siblingChunks: (json['siblingChunks'] as List<dynamic>?)
        ?.map((e) => SiblingChunkInfo.fromJson(e as Map<String, dynamic>))
        .toList() ?? [],
  );

  String toJsonString() => jsonEncode(toJson());

  static ChunkManifest fromJsonString(String jsonStr) =>
      ChunkManifest.fromJson(jsonDecode(jsonStr) as Map<String, dynamic>);
}

/// Binary Chunk Envelope packaging the sliced payload along with sibling manifest.
/// Magic: 'NVCP' (4 bytes) | Version (4 bytes) | ManifestLen (4 bytes) | PayloadLen (8 bytes)
class ChunkEnvelope {
  static const List<int> magicBytes = [0x4E, 0x56, 0x43, 0x50]; // 'NVCP'
  static const int headerSize = 20;

  final ChunkManifest? manifest;
  final Uint8List payloadBytes;

  ChunkEnvelope({this.manifest, required this.payloadBytes});

  /// Packs a manifest and raw payload bytes into an autonomous ChunkEnvelope binary.
  static Uint8List pack(ChunkManifest manifest, Uint8List payloadBytes) {
    final manifestBytes = utf8.encode(manifest.toJsonString());
    final manifestLen = manifestBytes.length;
    final payloadLen = payloadBytes.length;
    final totalSize = headerSize + manifestLen + payloadLen;

    final bd = ByteData(headerSize);
    bd.setUint8(0, magicBytes[0]);
    bd.setUint8(1, magicBytes[1]);
    bd.setUint8(2, magicBytes[2]);
    bd.setUint8(3, magicBytes[3]);
    bd.setInt32(4, manifest.version, Endian.big);
    bd.setInt32(8, manifestLen, Endian.big);
    bd.setInt64(12, payloadLen, Endian.big);

    final result = Uint8List(totalSize);
    result.setRange(0, headerSize, bd.buffer.asUint8List());
    result.setRange(headerSize, headerSize + manifestLen, manifestBytes);
    result.setRange(headerSize + manifestLen, totalSize, payloadBytes);
    return result;
  }

  /// Unpacks a ChunkEnvelope binary.
  /// If the binary does not contain the 'NVCP' magic header, safely treats the entire
  /// byte array as raw payload bytes for backward compatibility.
  static ChunkEnvelope unpack(Uint8List envelopeBytes) {
    if (envelopeBytes.length < headerSize) {
      return ChunkEnvelope(manifest: null, payloadBytes: envelopeBytes);
    }

    final hasMagic = envelopeBytes[0] == magicBytes[0] &&
                     envelopeBytes[1] == magicBytes[1] &&
                     envelopeBytes[2] == magicBytes[2] &&
                     envelopeBytes[3] == magicBytes[3];

    if (!hasMagic) {
      return ChunkEnvelope(manifest: null, payloadBytes: envelopeBytes);
    }

    final bd = ByteData.sublistView(envelopeBytes, 0, headerSize);
    final manifestLen = bd.getInt32(8, Endian.big);
    final payloadLen = bd.getInt64(12, Endian.big);

    if (envelopeBytes.length < headerSize + manifestLen + payloadLen || manifestLen < 0 || payloadLen < 0) {
      return ChunkEnvelope(manifest: null, payloadBytes: envelopeBytes);
    }

    try {
      final manifestJsonStr = utf8.decode(envelopeBytes.sublist(headerSize, headerSize + manifestLen));
      final manifest = ChunkManifest.fromJsonString(manifestJsonStr);
      final payload = envelopeBytes.sublist(headerSize + manifestLen, headerSize + manifestLen + payloadLen);
      return ChunkEnvelope(manifest: manifest, payloadBytes: payload);
    } catch (_) {
      return ChunkEnvelope(manifest: null, payloadBytes: envelopeBytes);
    }
  }
}

/// Dynamic Node-Based Chunking Engine for splitting and reassembling files.
/// Slices files into exactly M chunks corresponding to the number of active host nodes.
class FileChunker {
  /// Splits raw file bytes into dynamic chunk blocks based on the number of active host nodes.
  /// If [activeHostCount] is M (where M >= 1), creates exactly M balanced slices with zero byte loss.
  static List<Uint8List> splitIntoChunks(Uint8List fileBytes, {int? activeHostCount}) {
    if (fileBytes.isEmpty) return [Uint8List(0)];

    final hostCount = (activeHostCount != null && activeHostCount > 0) ? activeHostCount : 1;
    if (hostCount <= 1) {
      return [fileBytes];
    }

    final int totalLength = fileBytes.length;
    final int chunkCount = totalLength < hostCount ? totalLength : hostCount;
    if (chunkCount <= 1) {
      return [fileBytes];
    }

    final int baseChunkSize = totalLength ~/ chunkCount;
    final int remainder = totalLength % chunkCount;

    final List<Uint8List> chunks = [];
    int offset = 0;

    for (int i = 0; i < chunkCount; i++) {
      final int currentChunkSize = baseChunkSize + (i < remainder ? 1 : 0);
      final int end = offset + currentChunkSize;
      chunks.add(fileBytes.sublist(offset, end));
      offset = end;
    }

    return chunks;
  }

  /// Computes SHA-256 hex string for the given byte array.
  static String computeSha256(Uint8List bytes) {
    return sha256.convert(bytes).toString();
  }

  /// Reassembles downloaded chunk blocks into a single contiguous byte array.
  /// Automatically extracts payload if any chunk is packed in a ChunkEnvelope.
  static Uint8List reassembleChunks(List<Uint8List> chunks) {
    final List<Uint8List> unwrapped = chunks.map((c) => ChunkEnvelope.unpack(c).payloadBytes).toList();
    final totalLength = unwrapped.fold<int>(0, (sum, chunk) => sum + chunk.length);
    final result = Uint8List(totalLength);
    int offset = 0;

    for (final chunk in unwrapped) {
      result.setRange(offset, offset + chunk.length, chunk);
      offset += chunk.length;
    }

    return result;
  }
}
