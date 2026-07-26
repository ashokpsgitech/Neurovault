import 'dart:math';
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:pointycastle/export.dart';

/// Worker payload model for isolate computation.
class _CryptoParams {
  final Uint8List bytes;
  final Uint8List key;
  final int chunkIndex;

  _CryptoParams(this.bytes, this.key, this.chunkIndex);
}

Uint8List _encryptWorker(_CryptoParams params) {
  return CryptoEngine.encryptChunk(params.bytes, params.key, params.chunkIndex);
}

Uint8List _decryptWorker(_CryptoParams params) {
  return CryptoEngine.decryptChunk(params.bytes, params.key, params.chunkIndex);
}

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

  /// Asynchronously encrypts a chunk payload in a background worker isolate using compute.
  /// Prevents main UI thread freezing / stuttering during payload encryption.
  static Future<Uint8List> encryptChunkAsync(Uint8List plainBytes, Uint8List key, int chunkIndex) {
    return compute(_encryptWorker, _CryptoParams(plainBytes, key, chunkIndex));
  }

  /// Asynchronously decrypts an encrypted chunk payload in a background worker isolate using compute.
  /// Prevents main UI thread freezing / stuttering during payload decryption.
  static Future<Uint8List> decryptChunkAsync(Uint8List encryptedBytes, Uint8List key, int chunkIndex) {
    return compute(_decryptWorker, _CryptoParams(encryptedBytes, key, chunkIndex));
  }
}
