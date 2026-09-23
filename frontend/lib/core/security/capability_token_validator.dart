import 'dart:convert';

/// Result of capability token validation.
class CapabilityValidationResult {
  final bool isValid;
  final String? error;
  final Map<String, dynamic>? claims;

  const CapabilityValidationResult.valid([this.claims])
      : isValid = true,
        error = null;

  const CapabilityValidationResult.invalid(this.error)
      : isValid = false,
        claims = null;

  @override
  String toString() => isValid ? 'Valid' : 'Invalid: $error';
}

/// Validates structured capability tokens for chunk operations (READ, WRITE, REPLICATE).
class CapabilityTokenValidator {
  /// Validates a capability token against expected chunkId, operation, and optional hostId.
  static CapabilityValidationResult validate({
    required String? token,
    required String expectedChunkId,
    required String expectedOperation,
    String? expectedHostId,
    bool allowDevToken = false,
  }) {
    if (token == null || token.trim().isEmpty) {
      return const CapabilityValidationResult.invalid('Missing capability token');
    }

    final trimmed = token.trim().replaceFirst(RegExp(r'^Bearer\s+', caseSensitive: false), '');

    // Allow mock/dev tokens only if explicitly permitted
    if (allowDevToken && trimmed == 'dev-bypass-token') {
      return CapabilityValidationResult.valid({'sub': 'dev', 'operation': expectedOperation});
    }

    final parts = trimmed.split('.');
    if (parts.length != 3) {
      return const CapabilityValidationResult.invalid('Malformed token: expected 3-part JWT');
    }

    try {
      final payloadJson = _decodeBase64Url(parts[1]);
      final Map<String, dynamic> claims = jsonDecode(payloadJson) as Map<String, dynamic>;

      // 1. Verify expiration
      if (claims.containsKey('exp')) {
        final exp = claims['exp'];
        final int expSeconds = exp is int ? exp : int.tryParse(exp.toString()) ?? 0;
        final nowSeconds = DateTime.now().millisecondsSinceEpoch ~/ 1000;
        if (expSeconds <= nowSeconds) {
          return CapabilityValidationResult.invalid('Token expired at $expSeconds, current time is $nowSeconds');
        }
      }

      // 2. Verify chunkId claim
      final tokenChunkId = claims['chunkId'] ?? claims['chunk_id'];
      if (tokenChunkId != null && tokenChunkId.toString().isNotEmpty) {
        if (tokenChunkId.toString() != expectedChunkId) {
          return CapabilityValidationResult.invalid(
              'Token chunkId mismatch: expected "$expectedChunkId" but token is for "${tokenChunkId.toString()}"');
        }
      }

      // 3. Verify operation claim
      final tokenOp = claims['operation'] ?? claims['op'];
      if (tokenOp != null && tokenOp.toString().isNotEmpty) {
        final op = tokenOp.toString().toUpperCase();
        final expected = expectedOperation.toUpperCase();
        if (op != expected && op != 'REPLICATE') {
          return CapabilityValidationResult.invalid(
              'Token operation mismatch: required "$expected" but token is for "$op"');
        }
      }

      // 4. Verify hostId claim (if present and expectedHostId is specified)
      if (expectedHostId != null && expectedHostId.isNotEmpty) {
        final tokenHostId = claims['hostId'] ?? claims['host_id'];
        if (tokenHostId != null && tokenHostId.toString().isNotEmpty) {
          if (tokenHostId.toString() != expectedHostId) {
            return CapabilityValidationResult.invalid(
                'Token hostId mismatch: expected "$expectedHostId" but token is for "${tokenHostId.toString()}"');
          }
        }
      }

      return CapabilityValidationResult.valid(claims);
    } catch (e) {
      return CapabilityValidationResult.invalid('Failed to parse token payload: $e');
    }
  }

  static String _decodeBase64Url(String input) {
    String normalized = input.replaceAll('-', '+').replaceAll('_', '/');
    while (normalized.length % 4 != 0) {
      normalized += '=';
    }
    return utf8.decode(base64Decode(normalized));
  }
}
