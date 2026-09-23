# NeuroVault System Architecture & Trust Boundaries

**Version:** 1.0 (Beta Specification)  
**Target System:** Distributed Encrypted Cloud Storage Platform  

---

## 1. System Overview & Component Topology

NeuroVault partitions responsibilities between a central **Coordinator**, **Storage Hosts**, and **Client Applications**:

```
 ┌────────────────────────────────────────────────────────┐
 │                      Client Application                │
 │             (Flutter: Android / Desktop / Web)         │
 └───────────────┬────────────────────────┬───────────────┘
                 │                        │
       Metadata & Auth (TLS)     Encrypted Binary Chunks
                 │                        │
                 ▼                        ▼
 ┌───────────────────────────────┐ ┌──────────────────────┐
 │    Spring Boot Coordinator    │ │   Storage Host Node  │
 │  (Identity, Plan, Heartbeat)  │ │ (Chunk Server, NVLT) │
 └───────────────┬───────────────┘ └──────────────────────┘
                 │
                 ▼
 ┌───────────────────────────────┐
 │   Database / Metadata Store   │
 │   (User, Host, Session, Task) │
 └───────────────────────────────┘
```

### Components:
1. **Flutter Client Application (`frontend/lib/`)**:
   - Handles client-side cryptography (AES-256-GCM encryption/decryption).
   - Generates random Data Encryption Keys (DEKs) and wraps them with the user's Key Encryption Key (KEK).
   - Dynamically partitions files into $M$ chunks mapped to active host nodes.
   - Packages chunks with `NVCP` binary envelopes containing sibling chunk hashes and manifest.
2. **Spring Boot Coordinator (`backend/`)**:
   - Manages user identity, JWT authentication, and host registration.
   - Monitors node health via periodic heartbeats.
   - Computes weighted chunk placement plans and generates scoped chunk capability tokens.
   - Schedules and tracks durable replication and self-healing tasks.
3. **Storage Host Node (`frontend/lib/features/host/` & Android Service)**:
   - Allocates dedicated `storage.container` binary files with pre-allocated size and 256-byte `NVLT` header.
   - Serves authenticated direct HTTP chunk requests (`GET/POST /api/storage/chunks/{chunkId}`).
   - Runs as an Android Foreground Service with continuous heartbeat pinging.

---

## 2. Authoritative Sources of Truth

| Data Domain | Authoritative Store | Representation | Integrity Enforcement |
| :--- | :--- | :--- | :--- |
| **User Identity & Auth** | Coordinator DB | `User` entity (BCrypt passwords, JWT claims) | HMAC-SHA256 JWT signature verification |
| **Host Registration & Status** | Coordinator DB | `Host` entity (`ONLINE`, `DEGRADED`, `OFFLINE`) | Signed heartbeats with timeout threshold |
| **File Metadata & Structure** | Coordinator DB | `FileMetadata` & `Chunk` entities | SHA-256 full file hash + chunk hashes |
| **Chunk Redundancy Mapping** | Coordinator DB | `ChunkReplica` & `ReplicationTask` | Active replica count ($N \ge 2$) |
| **Encrypted Chunk Bytes** | Storage Host Disk | Direct offsets inside `storage.container` | CRC32 & SHA-256 in `NVLT` container index |
| **Encryption Keys (DEK)** | Client Device | Wrapped `encryptedAesKey` in metadata | KEK derived via PBKDF2/Argon2id (never plain on server) |

---

## 3. Trust Boundaries & Privacy Model

### What the Coordinator CAN see:
- User email, username, and account creation timestamps.
- File metadata: filename, file size, chunk counts, and cryptographic hashes (SHA-256).
- Host telemetry: IP address, available storage capacity, heartbeat latency.
- Wrapped encryption keys: `encryptedAesKey` (opaque ciphertext to the Coordinator).

### What the Coordinator CANNOT see:
- Plaintext file contents (chunk bytes stream directly to host nodes).
- Master encryption keys or unwrapped Data Encryption Keys (DEKs).

### What the Storage Host Node CAN see:
- Raw encrypted ciphertext bytes for stored chunks.
- Associated chunk ID and declared chunk byte length.

### What the Storage Host Node CANNOT see:
- Plaintext chunk contents.
- Master encryption keys or Data Encryption Keys.
- Original filename or other chunk relationships (envelopes contain sibling hashes, not sibling contents).
- User authentication tokens (Coordinator JWT tokens are stripped before peer host communication).

---

## 4. Failure Domains & Recovery Model

1. **Host Node Goes Offline**:
   - Coordinator detects missed heartbeats (> 90 seconds).
   - Node status transitions `ONLINE -> OFFLINE`.
   - `SelfHealingService` detects replica deficit ($R_{active} < R_{target}$) and queues durable `ReplicationTask`.
2. **Chunk Read Checksum Failure**:
   - `StorageEngine` verifies CRC32 and SHA-256 checksums on read.
   - Corrupted chunks throw `CorruptedChunkException`, failing the read and prompting the client to fall back to surviving replicas.
3. **Upload Interruption**:
   - Upload sessions maintain explicit TTLs (1 hour).
   - Incomplete sessions expire automatically, releasing reserved host capacities.
