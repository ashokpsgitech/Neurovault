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

    test('4MB File Chunker Splitting and Reassembly', () {
      final testData = Uint8List.fromList(List.generate(1000, (i) => i % 256));
      final chunks = FileChunker.splitIntoChunks(testData);

      expect(chunks.length, equals(1));
      expect(chunks[0], equals(testData));

      final reassembled = FileChunker.reassembleChunks(chunks);
      expect(reassembled, equals(testData));
    });
  });
}
