# NeuroVault Deep Analysis Report

## Scope
Repository analyzed at:

- `/home/runner/work/Neurovault/Neurovault`

Primary focus areas reviewed:

- Backend security/auth, storage, replication, self-healing
- Frontend Flutter app, Firebase integration, host networking
- Android foreground/background host service
- Data model and API behavior
- Test quality and CI/deployment controls
- Documentation-to-code alignment

## Executive Summary

The repository contains several high/critical-risk security and correctness issues concentrated around:

1. Authentication/authorization boundaries are not enforced consistently.
2. Storage APIs and host chunk-serving paths are vulnerable to misuse/abuse.
3. Cryptographic key handling on frontend violates zero-trust claims.
4. Deployment defaults and CI/testing controls are too weak for production safety.

Most urgent risks:

- Hardcoded JWT secret
- Object-level authorization bypass on storage host access
- Unvalidated chunk access tokens
- Plaintext-equivalent AES key handling in metadata
- Unauthenticated cleartext host chunk transport

## Methodology (What was reviewed)

Reviewed key code and configuration in:

- Backend config/security/auth:
  - `/home/runner/work/Neurovault/Neurovault/backend/src/main/resources/application.yml`
  - `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/config/SecurityConfig.java`
  - `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/security/JwtUtils.java`
  - `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/security/JwtAuthenticationFilter.java`
- Upload/download/storage flow:
  - `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/controller/FileController.java`
  - `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/upload/UploadService.java`
  - `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/download/DownloadService.java`
  - `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/storage/controller/StorageController.java`
  - `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/storage/service/StorageService.java`
  - `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/storage/engine/StorageEngine.java`
- Replication/self-healing/monitoring:
  - `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/replication/service/*.java`
  - `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/monitor/service/*.java`
- Frontend/Firebase/P2P host paths:
  - `/home/runner/work/Neurovault/Neurovault/frontend/lib/core/firebase/firebase_service.dart`
  - `/home/runner/work/Neurovault/Neurovault/frontend/lib/features/files/data/file_repository.dart`
  - `/home/runner/work/Neurovault/Neurovault/frontend/lib/features/files/services/file_service.dart`
  - `/home/runner/work/Neurovault/Neurovault/frontend/lib/core/network/dio_client.dart`
  - `/home/runner/work/Neurovault/Neurovault/frontend/lib/features/host/services/chunk_http_server.dart`
  - `/home/runner/work/Neurovault/Neurovault/frontend/lib/core/network/p2p_webrtc_service.dart`
- Android/network policy:
  - `/home/runner/work/Neurovault/Neurovault/frontend/android/app/src/main/AndroidManifest.xml`
  - `/home/runner/work/Neurovault/Neurovault/frontend/android/app/src/main/res/xml/network_security_config.xml`
- CI/docs/tests:
  - `/home/runner/work/Neurovault/Neurovault/.github/workflows/main_neurovault-coordinator.yml`
  - `/home/runner/work/Neurovault/Neurovault/README.md`
  - `/home/runner/work/Neurovault/Neurovault/DOCUMENTATION.md`
  - Backend and frontend test directories.

## Verified Findings

### 1) Hardcoded JWT secret in source
- **Severity:** Critical
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/backend/src/main/resources/application.yml:24-27`
- **Issue:** JWT signing secret is committed in repo.
- **Failure/attack scenario:** Anyone obtaining source/history can forge valid JWTs.
- **Remediation:** Remove secret from code, rotate keys immediately, use secret manager/env vars, add key rotation strategy.

### 2) Storage API IDOR / broken object-level authorization
- **Severity:** Critical
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/storage/controller/StorageController.java:162-165`, `134-156`, `109-129`
- **Issue:** If `hostId` is provided, it is accepted directly with no ownership enforcement.
- **Scenario:** Authenticated user reads/writes/deletes chunks on other users’ hosts.
- **Remediation:** Validate host ownership/permissions per request; reject foreign host IDs.

### 3) Client-controlled filesystem path accepted by backend
- **Severity:** High
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/storage/service/StorageService.java:80-91`
- **Issue:** `containerPath` from request is used directly for file operations.
- **Scenario:** Path traversal/unsafe writes in server filesystem namespace.
- **Remediation:** Server-only path policy under managed base dir; normalize and validate paths.

### 4) Wrong-container operations due to shared open container state
- **Severity:** Critical
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/storage/service/StorageService.java:293-301`
- **Issue:** Container reopened only if closed; not reopened when different host path requested.
- **Scenario:** Host B request may operate on Host A container.
- **Remediation:** Track/open per-host container context; verify opened path matches request.

### 5) Chunk read fallback returns wrong chunk
- **Severity:** High
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/storage/engine/StorageEngine.java:172-179`
- **Issue:** Missing chunk ID falls back to first active chunk.
- **Scenario:** Data corruption/leak in download reconstruction.
- **Remediation:** Strict not-found behavior; never substitute another chunk.

### 6) Integrity check is non-blocking
- **Severity:** High
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/storage/engine/StorageEngine.java:189-195`
- **Issue:** CRC mismatch only logs warning and still returns data.
- **Scenario:** Tampered/corrupt data consumed by clients.
- **Remediation:** Fail read, mark replica corrupted, trigger repair flow.

### 7) Upload session ownership/expiry validation missing
- **Severity:** High
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/upload/UploadService.java:155-157`, `299-304`; `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/upload/UploadSessionManager.java:94-98`
- **Issue:** Session retrieval lacks caller ownership and TTL enforcement.
- **Scenario:** Session hijack/cancel/finalize by UUID guessing/exposure.
- **Remediation:** Enforce user-session binding + expiry checks at service boundary.

### 8) Chunk operation tokens generated but not enforced
- **Severity:** High
- **Confidence:** High
- **Evidence:** Token generated in `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/coordinator/CoordinatorService.java:85-88`; storage endpoints in `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/storage/controller/StorageController.java:109-143`
- **Issue:** Token model exists but storage endpoints do not verify token claims.
- **Scenario:** Bypass intended chunk/host/session authorization.
- **Remediation:** Require and validate chunk token claims server-side for read/write.

### 9) Upload planning may allocate offline/fabricated targets
- **Severity:** High
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/upload/UploadService.java:75-78`, `100-111`; fallback in `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/coordinator/CoordinatorService.java:57-65`
- **Issue:** Falls back to all hosts / localhost defaults / random host UUIDs.
- **Scenario:** Unreliable uploads, metadata/data divergence, delivery failures.
- **Remediation:** Fail-fast when no eligible host set; do not emit synthetic host entries.

### 10) File hash semantics are weak/misleading
- **Severity:** Medium
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/upload/UploadService.java:174-196`
- **Issue:** Fallback hash is derived from concatenated chunk-hash strings or random placeholder.
- **Scenario:** Hash field cannot be trusted for true file integrity.
- **Remediation:** Require deterministic full-file hash contract and validation.

### 11) Frontend stores AES key in effectively plaintext form
- **Severity:** Critical
- **Confidence:** High
- **Evidence:** Key generated/encoded in `/home/runner/work/Neurovault/Neurovault/frontend/lib/features/files/data/file_repository.dart:49-52`; stored as `encryptedAesKey` in `/home/runner/work/Neurovault/Neurovault/frontend/lib/core/firebase/firebase_service.dart:444-450`
- **Issue:** Base64 key is not encrypted key wrapping.
- **Scenario:** Metadata access => decryption capability.
- **Remediation:** Asymmetric key wrapping/KMS envelope; never store raw DEK.

### 12) Authorization header leakage to host endpoints
- **Severity:** High
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/frontend/lib/core/network/dio_client.dart:21-25`; direct host requests in `/home/runner/work/Neurovault/Neurovault/frontend/lib/features/files/services/file_service.dart:42-50`, `93-101`
- **Issue:** Single Dio client adds auth header for all URLs, including direct host IPs.
- **Scenario:** Host receives user token/identity secret.
- **Remediation:** Split HTTP clients by trust domain; strip auth for peer/host traffic.

### 13) Host chunk server is unauthenticated and cleartext
- **Severity:** High
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/frontend/lib/features/host/services/chunk_http_server.dart:134-157`, `170-237`; cleartext policy in `/home/runner/work/Neurovault/Neurovault/frontend/android/app/src/main/res/xml/network_security_config.xml:9`
- **Issue:** Any reachable client can GET/POST chunks without auth; cleartext allowed.
- **Scenario:** LAN attacker reads/writes chunk payloads.
- **Remediation:** Authenticated requests (signed token/nonces), TLS/mTLS, narrow bind/network exposure.

### 14) Auth architecture mismatch (Firebase UID used as bearer token)
- **Severity:** High
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/frontend/lib/features/authentication/data/auth_repository.dart:20-23`; backend JWT expectation `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/security/JwtAuthenticationFilter.java:38-44`
- **Issue:** Frontend stores UID as token; backend expects signed JWT.
- **Scenario:** Backend auth failure and silent fallback behavior.
- **Remediation:** Unify identity model (backend JWT or Firebase JWT validation) and remove mixed token semantics.

### 15) Risky production defaults (`ddl-auto: create`, local H2 fallback)
- **Severity:** High
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/backend/src/main/resources/application.yml:9,15`
- **Issue:** Schema recreate behavior + dev DB default is unsafe for production.
- **Scenario:** Service restart can cause metadata loss.
- **Remediation:** Production profile with managed DB + migrations + `validate`.

### 16) CI allows deployment without tests
- **Severity:** Medium
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/.github/workflows/main_neurovault-coordinator.yml:37`
- **Issue:** Build step explicitly skips tests (`-x test`).
- **Scenario:** Vulnerable/regressive code reaches production.
- **Remediation:** Enforce tests in CI gates; add frontend test workflow.

### 17) Documentation mismatch with current repo state
- **Severity:** Medium
- **Confidence:** High
- **Evidence:** `/home/runner/work/Neurovault/Neurovault/README.md:257-259`; missing `scratch/e2e_workflow.py`
- **Issue:** Docs reference artifacts/flows that do not exist or differ from implementation.
- **Scenario:** Operator misunderstanding and false assurance.
- **Remediation:** Align docs to actual runtime architecture and assets.

## Hypotheses Requiring Runtime Validation

### 1) Firestore rules might permit over-broad access
- **Confidence:** Medium
- **Code paths:** `/home/runner/work/Neurovault/Neurovault/frontend/lib/core/firebase/firebase_service.dart`, `/home/runner/work/Neurovault/Neurovault/frontend/lib/core/network/p2p_webrtc_service.dart`
- **Need:** Inspect deployed Firestore security rules.

### 2) External exploitability of chunk HTTP server depends on network topology
- **Confidence:** Medium
- **Code path:** `/home/runner/work/Neurovault/Neurovault/frontend/lib/features/host/services/chunk_http_server.dart:36`
- **Need:** Real-network penetration test (LAN/WAN/NAT/router conditions).

### 3) Cross-host container confusion impact depends on deployment pattern
- **Confidence:** Medium
- **Code path:** `/home/runner/work/Neurovault/Neurovault/backend/src/main/java/com/neurovault/backend/storage/service/StorageService.java:293-301`
- **Need:** Concurrent multi-host integration load tests in production-like topology.

## Test and CI Quality Assessment

### Backend tests
- Coverage breadth exists across controller/replication/storage modules.
- Major gap: adversarial security tests (IDOR, unauthorized host access, token misuse, session hijack, path injection, integrity-failure enforcement).
- Existing tests are mostly functional/happy-path.

### Frontend tests
- Very limited (`widget_test.dart`, `crypto_test.dart`, `container_test.dart`).
- No robust integration tests for auth-flow coherence, Firebase rule behavior, host networking, or secure transport guarantees.

### CI pipeline
- Deploy workflow skips tests.
- No visible frontend CI test/lint/security workflow in `.github/workflows`.

## Priority Remediation Order (Recommended)

### Immediate (P0)
- Rotate/remove JWT secret.
- Fix storage IDOR and enforce host ownership checks.
- Enforce chunk token validation on storage endpoints.
- Remove wrong-chunk fallback and hard-fail integrity mismatches.
- Stop storing raw/base64 AES keys as `encryptedAesKey`.

### Near-term (P1)
- Remove client-provided storage path control.
- Separate trusted/untrusted HTTP clients in frontend to prevent auth leakage.
- Require authenticated + encrypted transport for host chunk server.
- Add session ownership and expiry checks in upload/download session flows.

### Hardening (P2)
- Align auth architecture (Firebase vs backend JWT) into one verifiable model.
- Introduce prod-safe DB profiles/migrations.
- Strengthen CI gates and security-focused tests.
- Reconcile docs with implemented behavior.
