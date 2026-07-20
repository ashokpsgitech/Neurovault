package com.neurovault.backend.encryption;

/**
 * Holds the result of an AES-256-GCM encryption operation.
 * Contains the initialization vector, the SHA-256 hash of the original plaintext,
 * and the original plaintext size for metadata persistence.
 *
 * @param iv             the 12-byte initialization vector used during encryption
 * @param plaintextHash  the SHA-256 hex digest of the original plaintext file
 * @param plaintextSize  the size in bytes of the original plaintext file
 */
public record EncryptionResult(
        byte[] iv,
        String plaintextHash,
        long plaintextSize
) {
}
