# NeuroVault Implementation Audit & Security Analysis Report

**Date:** September 2026  
**Repository:** `ashokpsgitech/Neurovault`  
**Auditor:** Antigravity Autonomous Security & Architecture Agent  
**Baseline Test Results:** Backend 129/129 Passing | Frontend 8/8 Passing

---

## 1. Executive Summary

A comprehensive architectural and security audit of the **NeuroVault** distributed encrypted storage platform was conducted across:
- Backend Spring Boot Coordinator & Storage Engine
- Frontend Flutter multi-platform application (Web, Android, Desktop)
- Cryptographic key management & data envelope packaging
- Android foreground host service and local HTTP chunk server
- CI/CD quality gates and deployment pipelines

The audit identified **17 verified findings** across security, reliability, and data integrity. While the project exhibits a strong architectural blueprint and functioning core components, several critical vulnerabilities—including IDOR in storage APIs, plaintext key exposure in metadata, unauthenticated chunk servers, and silent data corruption acceptance—must be hardened before beta deployment.

---

## 2. Feature Claim vs. Implementation Matrix

| Feature Domain | Claimed in README | Implemented in Code | Actual State & Verification Status |
| :--- | :--- | :--- | :--- |
| **AES-256-GCM Encryption** | Zero-trust client-side encryption | ✅ Implemented | File payload encrypted in Dart isolates using AES-256-GCM with 96-bit random nonce & 128-bit authentication tag. |
| **Key Management** | Zero-knowledge security | ⚠️ Partial / Critical Risk | AES DEK was base64-encoded and stored directly in Firestore/metadata without Master Key wrapping (Remediated in Milestone 3). |
| **Dynamic Chunking** | Node-based dynamic slicing | ✅ Implemented | Implemented in `file_chunker.dart` ($M$-way balanced partitioning across active hosts) with `NVCP` envelopes. |
| **Storage Container I/O** | Pre-allocated binary container file | ✅ Implemented | Implemented in `host_repository.dart` and `ContainerManager.java` with 256-byte `NVLT` header & direct offset reads/writes. |
| **Host IDOR Protection** | Tenant isolation | ❌ Broken / High Risk | `StorageController.java` accepted arbitrary `hostId` without ownership checks. |
| **Chunk Integrity Check** | Fail-safe integrity verification | ❌ Bugged | `StorageEngine.java` only logged a warning on CRC32 mismatch and fell back to the first available chunk on missing ID. |
| **Upload Session Ownership** | Authorized session completion | ❌ Broken | `UploadService.java` permitted any authenticated user to complete or cancel foreign upload sessions. |
| **Self-Healing & Replication** | Autonomous healing engine | ⚠️ Partial | In-memory scheduler logic existed in `SelfHealingService.java`; durable task persistence and byte-level replication needed. |
| **Host Chunk Transport** | Peer-to-peer secure chunk transfer | ❌ Broken / High Risk | `ChunkHttpServer.dart` served chunks on LAN over plain HTTP without request authentication. |
| **CI Quality Gate** | Automated build & verification | ❌ Disabled | Azure deployment workflow skipped tests with `-x test`. |

---

## 3. Verified Findings & Vulnerability Register

### Finding 1: Hardcoded JWT Secret in Source
- **Severity:** 🔴 Critical
- **Confidence:** High
- **Evidence:** `backend/src/main/resources/application.yml:26`
- **Impact:** Anyone with read access to the repo can forge valid JWT administrative or client tokens.
- **Remediation:** Externalize secret to `${APP_JWT_SECRET}`; add startup validation preventing execution with default secret in production.

### Finding 2: Storage API Insecure Direct Object Reference (IDOR)
- **Severity:** 🔴 Critical
- **Confidence:** High
- **Evidence:** `backend/src/main/java/com/neurovault/backend/storage/controller/StorageController.java:162-165`
- **Impact:** Any authenticated user can read, store, or delete chunks in any other user's host container simply by providing the target `hostId`.
- **Remediation:** Strictly validate caller ownership of target `hostId` using authenticated `Principal`.

### Finding 3: Client-Controlled Filesystem Path Injection
- **Severity:** 🟠 High
- **Confidence:** High
- **Evidence:** `backend/src/main/java/com/neurovault/backend/storage/service/StorageService.java:82-91`
- **Impact:** Client request could specify arbitrary host filesystem paths for container creation, risking path traversal and unauthorized disk writes.
- **Remediation:** Enforce server-managed base directory (`./neurovault-storage/{hostId}/`) and reject arbitrary absolute paths from clients.

### Finding 4: Multi-Host Container State Confusion
- **Severity:** 🔴 Critical
- **Confidence:** High
- **Evidence:** `backend/src/main/java/com/neurovault/backend/storage/service/StorageService.java:293-301`
- **Impact:** `ContainerManager` singleton cached a single open file channel; subsequent requests for different host containers did not re-open the container file, leading to cross-host container contamination.
- **Remediation:** Verify that the open container path strictly matches the target host ID or track per-host container instances.

### Finding 5: Chunk Read Fallback Returns Wrong Chunk
- **Severity:** 🟠 High
- **Confidence:** High
- **Evidence:** `backend/src/main/java/com/neurovault/backend/storage/engine/StorageEngine.java:174-179`
- **Impact:** When a requested chunk ID was missing, `readChunk` fell back to returning the first active chunk in the container, corrupting reconstructed downloads.
- **Remediation:** Strictly throw `ChunkNotFoundException` when a chunk ID is not found.

### Finding 6: Non-Blocking Integrity Verification
- **Severity:** 🟠 High
- **Confidence:** High
- **Evidence:** `backend/src/main/java/com/neurovault/backend/storage/engine/StorageEngine.java:189-195`
- **Impact:** CRC32 checksum failure only logged a warning and still returned corrupted bytes to caller.
- **Remediation:** Hard-fail and throw `CorruptedChunkException` on checksum or hash mismatch.

### Finding 7: Missing Upload Session Caller Ownership
- **Severity:** 🟠 High
- **Confidence:** High
- **Evidence:** `backend/src/main/java/com/neurovault/backend/upload/UploadService.java:155-157`
- **Impact:** An attacker could finalize or cancel another user's upload session by supplying their session UUID.
- **Remediation:** Validate `session.getUser().getId().equals(authenticatedUser.getId())`.

### Finding 8: Unenforced Chunk Operation Tokens
- **Severity:** 🟠 High
- **Confidence:** High
- **Evidence:** `backend/src/main/java/com/neurovault/backend/coordinator/CoordinatorService.java:85-88`
- **Impact:** While `CoordinatorService` generated signed chunk JWT tokens, `StorageController` never checked or validated them on chunk operations.
- **Remediation:** Validate token claims on direct chunk upload and read endpoints.

### Finding 9: Fabricated Synthetic Host Allocations
- **Severity:** 🟠 High
- **Confidence:** High
- **Evidence:** `backend/src/main/java/com/neurovault/backend/upload/UploadService.java:100-111`
- **Impact:** When no online hosts existed, upload planner generated random UUIDs and dummy IPs (`127.0.0.1`), leading to silent upload failure.
- **Remediation:** Throw `InsufficientHostsException` and fail fast when no healthy hosts are online.

### Finding 10: Non-Deterministic / Placeholder File Hash Semantics
- **Severity:** 🟡 Medium
- **Confidence:** High
- **Evidence:** `backend/src/main/java/com/neurovault/backend/upload/UploadService.java:174-196`
- **Impact:** File hash fell back to concatenating chunk hash strings or random UUIDs.
- **Remediation:** Enforce deterministic SHA-256 validation of the full file payload.

### Finding 11: Plaintext-Equivalent AES Key Storage in Metadata
- **Severity:** 🔴 Critical
- **Confidence:** High
- **Evidence:** `frontend/lib/features/files/data/file_repository.dart:49-52`
- **Impact:** Symmetric DEK was stored as base64 string under `encryptedAesKey`. Anyone reading metadata obtained complete decryption capability.
- **Remediation:** Implement Master Key derivation (PBKDF2/Argon2id) and key wrapping (AES-KW) so only the user possessing credentials can unwrap the DEK.

### Finding 12: Bearer JWT Leakage to Direct Host Endpoints
- **Severity:** 🟠 High
- **Confidence:** High
- **Evidence:** `frontend/lib/core/network/dio_client.dart:21-25`
- **Impact:** The same Dio HTTP client automatically attached the user's Coordinator JWT to all HTTP requests, including direct peer-to-peer host nodes.
- **Remediation:** Strip bearer tokens for external host URLs and isolate coordinator requests.

### Finding 13: Unauthenticated Cleartext Host Chunk Server
- **Severity:** 🟠 High
- **Confidence:** High
- **Evidence:** `frontend/lib/features/host/services/chunk_http_server.dart:140-147`
- **Impact:** Anyone on the local network could issue GET/POST requests to `/api/storage/chunks/{chunkId}` without any credentials.
- **Remediation:** Require signed capability tokens on incoming HTTP chunk requests.

### Finding 14: Authentication Architecture Dual Identity Mismatch
- **Severity:** 🟠 High
- **Confidence:** High
- **Evidence:** `frontend/lib/features/authentication/data/auth_repository.dart:20-23`
- **Impact:** Firebase UID was passed as bearer token in some flows while Spring Boot backend expected signed HMAC-SHA256 JWTs.
- **Remediation:** Unify identity model with backend JWT exchange for all coordinator calls.

### Finding 15: Risky Database Defaults (`ddl-auto: create`)
- **Severity:** 🟠 High
- **Confidence:** High
- **Evidence:** `backend/src/main/resources/application.yml:15`
- **Impact:** Server restart in production would wipe database tables and recreate schema.
- **Remediation:** Separate `dev` and `prod` profiles; enforce `ddl-auto: update` for dev and `validate` for production.

### Finding 16: CI Allows Deployment Without Running Tests
- **Severity:** 🟡 Medium
- **Confidence:** High
- **Evidence:** `.github/workflows/main_neurovault-coordinator.yml:37`
- **Impact:** `./gradlew bootJar -x test` explicitly skipped test execution prior to Azure deployment.
- **Remediation:** Enforce `./gradlew test` in build step and create separate CI verification workflow.

### Finding 17: Documentation Mismatch with Repo State
- **Severity:** 🟡 Medium
- **Confidence:** High
- **Evidence:** `README.md:257-259`
- **Impact:** Documentation referenced outdated files (`scratch/e2e_workflow.py`) and overstated "zero-knowledge" and "self-healing" readiness.
- **Remediation:** Update documentation to reflect actual verified beta capabilities.

---

## 4. Prioritized Implementation Roadmap

1. **Milestone 1 — Baseline, Safety & Audit Documentation** (Current)
2. **Milestone 2 — Backend Security & Authorization Hardening** (P0 IDOR, Container isolation, Chunk integrity, Session validation)
3. **Milestone 3 — Frontend Cryptography & Transport Hardening** (Key wrapping, Dio client isolation, Host server token validation)
4. **Milestone 4 — Self-Healing & Durable Replication Engine** (Durable task entity, lease locking, repair execution)
5. **Milestone 5 — Production Documentation & Operations Guide** (Security model, API contract, Operations manual)
