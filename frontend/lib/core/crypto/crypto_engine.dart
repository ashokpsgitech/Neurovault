import 'dart:math';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:pointycastle/export.dart';

/// Client-Side Cryptographic Engine for NeuroVault.
/// Implements Zero-Trust AES-256-GCM symmetric payload encryption,
/// SHA-256 integrity verification, and secure key derivation.
class CryptoEngine {
  /// Calculates SHA-256 checksum hex string for chunk payload validation.
  static String calculateSha256(List<int> bytes) {
    final digest = sha256.convert(bytes);
    return digest.toString();
  }

  /// Generates a random 256-bit (32 byte) symmetric AES encryption key using CSPRNG.
  static Uint8List generateSymmetricKey() {
    final random = Random.secure();
    return Uint8List.fromList(List<int>.generate(32, (_) => random.nextInt(256)));
  }

  /// Encrypts a chunk payload using AES-256-GCM with CSPRNG nonce.
  /// Result format: [12-byte Nonce] + [Ciphertext + 16-byte Auth Tag MAC]
  static Uint8List encryptChunk(Uint8List plainBytes, Uint8List key, int chunkIndex) {
    final random = Random.secure();
    final nonce = Uint8List.fromList(List<int>.generate(12, (_) => random.nextInt(256)));

    final cipher = GCMBlockCipher(AESEngine());
    cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, Uint8List(0)));

    final out = cipher.process(plainBytes);

    final result = Uint8List(12 + out.length);
    result.setAll(0, nonce);
    result.setAll(12, out);
    return result;
  }

  /// Decrypts an encrypted chunk payload back to raw unencrypted bytes using AES-256-GCM.
  static Uint8List decryptChunk(Uint8List encryptedBytes, Uint8List key, int chunkIndex) {
    if (encryptedBytes.length < 12 + 16) {
      throw Exception('Invalid encrypted payload size for AES-GCM decryption');
    }
    final nonce = encryptedBytes.sublist(0, 12);
    final cipherTextWithTag = encryptedBytes.sublist(12);

    final cipher = GCMBlockCipher(AESEngine());
    cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, Uint8List(0)));

    return cipher.process(cipherTextWithTag);
  }
}

