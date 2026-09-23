# NeuroVault Security Architecture & Threat Model

**Version:** 1.0 (Beta Specification)  
**Security Classification:** Confidential / Production Hardened  

---

## 1. Threat Model & Security Scope

NeuroVault is designed as a distributed encrypted storage platform where client data privacy and integrity are preserved across untrusted networks and semi-trusted storage nodes.

### 1.1 Threat Actors & Vectors

| Threat Actor | Capabilities & Surface | Mitigation Strategy |
| :--- | :--- | :--- |
| **Malicious Client / Attacker** | Network access, forged requests, IDOR probes | Strict ownership validation in controllers and services; JWT signature verification. |
| **Compromised Storage Host** | Inspects host disk, monitors local chunk HTTP traffic | Client-side AES-256-GCM encryption; storage hosts store only ciphertext chunks in opaque `storage.container` binary files. |
| **Passive Network Eavesdropper** | LAN / WAN traffic sniffing | TLS encryption on all Coordinator APIs; signed scoped capability tokens on chunk transfers. |
| **Malicious Peer Node** | Tampering with stored chunks | Non-blocking CRC32 & SHA-256 integrity checks; corrupted chunks throw `CorruptedChunkException` triggering self-healing. |
| **Metadata DB Read Attacker** | Compromises Coordinator database or Firestore | Envelope Encryption: Data Encryption Keys (DEKs) are wrapped with user Key Encryption Keys (KEK); server never stores raw plaintext keys. |

---

## 2. Cryptographic Architecture & Key Lifecycle

```
 User Passphrase / Seed
          │
          ▼  (PBKDF2-HMAC-SHA256, 10,000 iterations + Salt)
 ┌──────────────────────┐
 │  Master KEK (256-bit)│  (Stored ONLY in Client Secure Storage)
 └──────────┬───────────┘
            │
            ▼  (AES-256-GCM Key Wrap)
 ┌──────────────────────────────────────────────┐
 │  Wrapped DEK: [12B Nonce] [Encrypted DEK] [16B Tag] │  ==> Stored in File Metadata
 └──────────────────────────────────────────────┘
            │
            ▼  (Unwrapped in Dart worker isolate during download)
 ┌──────────────────────┐
 │  Symmetric DEK (32B) │
 └──────────┬───────────┘
            │
            ▼  (AES-256-GCM Payload Encryption)
 ┌──────────────────────────────────────────────┐
 │  Chunk Ciphertext: [12B Nonce] [Bytes] [16B Tag]    │  ==> Stored in Host Container
 └──────────────────────────────────────────────┘
```

### 2.1 Cryptographic Primitives:
- **Cipher:** AES-256 in Galois/Counter Mode (GCM).
- **Authentication Tag:** 128-bit MAC tag verified on every decryption.
- **Nonce Generation:** 96-bit (12-byte) Cryptographically Secure Pseudo-Random Number Generator (`Random.secure()`). Nonces are never reused with the same key.
- **Key Derivation (KDF):** PBKDF2 with HMAC-SHA256, 10,000 rounds, 32-byte salt.
- **Integrity Digest:** SHA-256 calculated over raw payloads and chunk slices; CRC32 fast-checksum indexed in `NVLT` container headers.

---

## 3. Authorization & IDOR Prevention

### 3.1 Principle of Least Privilege
Every state-mutating and resource-reading endpoint verifies the authenticated `Principal`:

1. **Host Node Ownership:**
   - Looking up host status (`GET /api/host/{hostId}`) or creating containers (`POST /api/storage/create`) verifies `host.ownerId == principal.userId`.
   - Unauthorized attempts throw `AccessDeniedException` (HTTP 403).
2. **Heartbeat Protection:**
   - Host heartbeat requests verify that the target host exists and belongs to the authenticated caller, preventing host impersonation.
3. **Upload Session Binding:**
   - Finalizing (`POST /api/files/upload-complete`), cancelling, or querying progress of upload sessions validates `session.user.id == principal.userId`.
   - Expired sessions (> 1 hour) are automatically marked `FAILED` and rejected.

### 3.2 Scoped Chunk Capability Tokens
Direct chunk operations on distributed storage host nodes bypass central coordinator bandwidth while maintaining zero-trust authorization:
- The Coordinator issues short-lived JWT capability tokens with subject:
  `chunk-session:<sessionId>:host:<hostId>:index:<chunkIndex>`
- Storage host endpoints (`POST /api/storage/chunks` and `GET /api/storage/chunks/{chunkId}`) require either:
  1. Host owner credentials, OR
  2. A valid, unexpired `X-Chunk-Token` capability token issued by the Coordinator.

---

## 4. Container Storage Isolation

Host storage is confined strictly to pre-allocated binary containers:
- **Location:** Managed server directory `./neurovault-storage/{hostId}/storage.container`.
- **Path Traversal Protection:** Arbitrary client-provided filesystem paths are validated and confined to the managed base directory.
- **Multi-Host Switching:** Container file channels are tracked per host; requests for different hosts automatically rebind the active container file.
- **Strict Integrity Checks:** Reads verify CRC32 and SHA-256; corrupted data is rejected with `CorruptedChunkException`.
