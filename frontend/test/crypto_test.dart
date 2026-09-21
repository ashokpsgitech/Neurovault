import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:neurovault_frontend/core/crypto/crypto_engine.dart';
import 'package:neurovault_frontend/core/crypto/file_chunker.dart';

void main() {
  group('Zero-Trust Client Crypto Engine Tests', () {
    test('SHA-256 Checksum Calculation', () {
      final bytes = Uint8List.fromList([1, 2, 3, 4, 5]);
      final checksum = CryptoEngine.calculateSha256(bytes);
      expect(checksum, isNotEmpty);
      expect(checksum.length, equals(64)); // 256 bits in hex
    });

    test('AES-256-GCM Encryption and Decryption Round-Trip', () {
      final key = CryptoEngine.generateSymmetricKey();
      final plainText = Uint8List.fromList([78, 101, 117, 114, 111, 86, 97, 117, 108, 116]); // "NeuroVault"

      final encrypted = CryptoEngine.encryptChunk(plainText, key, 0);
      expect(encrypted, isNot(equals(plainText)));

      final decrypted = CryptoEngine.decryptChunk(encrypted, key, 0);
      expect(decrypted, equals(plainText));
    });

    test('Node-Based File Chunker: Single Node (M=1)', () {
      final testData = Uint8List.fromList(List.generate(1000, (i) => i % 256));
      final chunks = FileChunker.splitIntoChunks(testData, activeHostCount: 1);

      expect(chunks.length, equals(1));
      expect(chunks[0], equals(testData));

      final reassembled = FileChunker.reassembleChunks(chunks);
      expect(reassembled, equals(testData));
    });

    test('Node-Based File Chunker: Multi-Node Splitting (M=3 and M=5)', () {
      final testData = Uint8List.fromList(List.generate(10000, (i) => i % 256));

      // Test M = 3 active hosts -> exactly 3 chunks
      final chunks3 = FileChunker.splitIntoChunks(testData, activeHostCount: 3);
      expect(chunks3.length, equals(3));
      final totalLen3 = chunks3.fold<int>(0, (sum, c) => sum + c.length);
      expect(totalLen3, equals(10000));
      expect(FileChunker.reassembleChunks(chunks3), equals(testData));

      // Test M = 5 active hosts -> exactly 5 chunks
      final chunks5 = FileChunker.splitIntoChunks(testData, activeHostCount: 5);
      expect(chunks5.length, equals(5));
      for (final chunk in chunks5) {
        expect(chunk.length, equals(2000));
      }
      expect(FileChunker.reassembleChunks(chunks5), equals(testData));
    });

    test('ChunkEnvelope: Pack and Unpack with Sibling Manifest', () {
      final payload = Uint8List.fromList([10, 20, 30, 40, 50, 60, 70, 80]);
      final siblings = [
        SiblingChunkInfo(
          chunkIndex: 0,
          chunkId: 'file_test_chunk_0',
          sizeBytes: 8,
          sha256: FileChunker.computeSha256(payload),
          assignedHostId: 'host_android_1',
          assignedHostname: 'Pixel-8',
          replicaHostIds: ['host_android_1', 'host_backup_2'],
        ),
        SiblingChunkInfo(
          chunkIndex: 1,
          chunkId: 'file_test_chunk_1',
          sizeBytes: 8,
          sha256: 'abc123sha256',
          assignedHostId: 'host_windows_2',
          assignedHostname: 'Surface-Pro',
          replicaHostIds: ['host_windows_2'],
        ),
      ];

      final manifest = ChunkManifest(
        fileId: 'file_test_123',
        filename: 'report.pdf',
        fileSizeBytes: 16,
        totalChunks: 2,
        currentChunkIndex: 0,
        currentChunkId: 'file_test_chunk_0',
        siblingChunks: siblings,
      );

      final envelopeBytes = ChunkEnvelope.pack(manifest, payload);
      expect(envelopeBytes.length, greaterThan(payload.length + 20));

      final unpacked = ChunkEnvelope.unpack(envelopeBytes);
      expect(unpacked.manifest, isNotNull);
      expect(unpacked.manifest!.fileId, equals('file_test_123'));
      expect(unpacked.manifest!.totalChunks, equals(2));
      expect(unpacked.manifest!.currentChunkIndex, equals(0));
      expect(unpacked.manifest!.siblingChunks.length, equals(2));
      expect(unpacked.manifest!.siblingChunks[0].assignedHostId, equals('host_android_1'));
      expect(unpacked.manifest!.siblingChunks[1].assignedHostname, equals('Surface-Pro'));
      expect(unpacked.payloadBytes, equals(payload));
    });

    test('End-to-End: Node-Based Dynamic Chunking with Envelopes Reassembly', () {
      final originalData = Uint8List.fromList(List.generate(5000, (i) => (i * 7) % 256));
      final int activeHosts = 3;

      // 1. Slicing based on active host count
      final rawSlices = FileChunker.splitIntoChunks(originalData, activeHostCount: activeHosts);
      expect(rawSlices.length, equals(3));

      // 2. Build Sibling manifest
      final List<SiblingChunkInfo> siblingList = [];
      for (int i = 0; i < rawSlices.length; i++) {
        siblingList.add(SiblingChunkInfo(
          chunkIndex: i,
          chunkId: 'file_e2e_chunk_$i',
          sizeBytes: rawSlices[i].length,
          sha256: FileChunker.computeSha256(rawSlices[i]),
          assignedHostId: 'host_$i',
          assignedHostname: 'Node-$i',
          replicaHostIds: ['host_$i'],
        ));
      }

      // 3. Pack each chunk slice into a ChunkEnvelope
      final List<Uint8List> envelopes = [];
      for (int i = 0; i < rawSlices.length; i++) {
        final manifest = ChunkManifest(
          fileId: 'file_e2e_999',
          filename: 'cluster_data.bin',
          fileSizeBytes: originalData.length,
          totalChunks: rawSlices.length,
          currentChunkIndex: i,
          currentChunkId: 'file_e2e_chunk_$i',
          siblingChunks: siblingList,
        );
        envelopes.add(ChunkEnvelope.pack(manifest, rawSlices[i]));
      }

      // 4. Reassemble chunks directly from envelopes
      final reassembled = FileChunker.reassembleChunks(envelopes);
      expect(reassembled, equals(originalData));
    });
  });
}
