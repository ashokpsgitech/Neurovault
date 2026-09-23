import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:neurovault_frontend/core/security/capability_token_validator.dart';

void main() {
  group('CapabilityTokenValidator Tests', () {
    String generateJwt(Map<String, dynamic> claims) {
      final header = base64Url.encode(utf8.encode(jsonEncode({'alg': 'HS256', 'typ': 'JWT'}))).replaceAll('=', '');
      final payload = base64Url.encode(utf8.encode(jsonEncode(claims))).replaceAll('=', '');
      final signature = 'fake_signature_bytes';
      return '$header.$payload.$signature';
    }

    test('Rejects null or empty token', () {
      final res = CapabilityTokenValidator.validate(
        token: null,
        expectedChunkId: 'chunk_123',
        expectedOperation: 'READ',
      );
      expect(res.isValid, isFalse);
      expect(res.error, contains('Missing capability token'));
    });

    test('Rejects malformed token without 3 parts', () {
      final res = CapabilityTokenValidator.validate(
        token: 'not-a-valid-jwt',
        expectedChunkId: 'chunk_123',
        expectedOperation: 'READ',
      );
      expect(res.isValid, isFalse);
      expect(res.error, contains('Malformed token'));
    });

    test('Rejects token with expired exp', () {
      final expiredEpoch = (DateTime.now().millisecondsSinceEpoch ~/ 1000) - 300;
      final jwt = generateJwt({
        'chunkId': 'chunk_123',
        'operation': 'READ',
        'exp': expiredEpoch,
      });

      final res = CapabilityTokenValidator.validate(
        token: jwt,
        expectedChunkId: 'chunk_123',
        expectedOperation: 'READ',
      );
      expect(res.isValid, isFalse);
      expect(res.error, contains('expired'));
    });

    test('Rejects token with mismatched chunkId', () {
      final futureEpoch = (DateTime.now().millisecondsSinceEpoch ~/ 1000) + 3600;
      final jwt = generateJwt({
        'chunkId': 'chunk_other',
        'operation': 'READ',
        'exp': futureEpoch,
      });

      final res = CapabilityTokenValidator.validate(
        token: jwt,
        expectedChunkId: 'chunk_123',
        expectedOperation: 'READ',
      );
      expect(res.isValid, isFalse);
      expect(res.error, contains('chunkId mismatch'));
    });

    test('Rejects WRITE token for READ operation', () {
      final futureEpoch = (DateTime.now().millisecondsSinceEpoch ~/ 1000) + 3600;
      final jwt = generateJwt({
        'chunkId': 'chunk_123',
        'operation': 'WRITE',
        'exp': futureEpoch,
      });

      final res = CapabilityTokenValidator.validate(
        token: jwt,
        expectedChunkId: 'chunk_123',
        expectedOperation: 'READ',
      );
      expect(res.isValid, isFalse);
      expect(res.error, contains('operation mismatch'));
    });

    test('Rejects READ token for WRITE operation', () {
      final futureEpoch = (DateTime.now().millisecondsSinceEpoch ~/ 1000) + 3600;
      final jwt = generateJwt({
        'chunkId': 'chunk_123',
        'operation': 'READ',
        'exp': futureEpoch,
      });

      final res = CapabilityTokenValidator.validate(
        token: jwt,
        expectedChunkId: 'chunk_123',
        expectedOperation: 'WRITE',
      );
      expect(res.isValid, isFalse);
      expect(res.error, contains('operation mismatch'));
    });

    test('Validates matching non-expired token successfully', () {
      final futureEpoch = (DateTime.now().millisecondsSinceEpoch ~/ 1000) + 3600;
      final jwt = generateJwt({
        'chunkId': 'chunk_123',
        'operation': 'WRITE',
        'hostId': 'host_abc',
        'exp': futureEpoch,
      });

      final res = CapabilityTokenValidator.validate(
        token: 'Bearer $jwt',
        expectedChunkId: 'chunk_123',
        expectedOperation: 'WRITE',
        expectedHostId: 'host_abc',
      );
      expect(res.isValid, isTrue);
      expect(res.claims?['chunkId'], equals('chunk_123'));
    });
  });
}
