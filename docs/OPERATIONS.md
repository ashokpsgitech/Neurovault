# NeuroVault Operations & Deployment Manual

**Target Environment:** Azure App Service / Self-Hosted Docker Cluster  
**Runtime:** Java 21 LTS (Spring Boot 3.3.x) & Flutter 3.x  

---

## 1. Environment Configuration

### Required Environment Variables:

| Variable | Description | Example / Recommended Value |
| :--- | :--- | :--- |
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `prod` (production) or `dev` (development) |
| `APP_JWT_SECRET` | 256-bit+ HMAC-SHA256 signing key | Base64 or 64-char hex secret from Key Vault |
| `APP_JWT_EXPIRATION_MS` | JWT validity window | `86400000` (24 hours) |
| `SPRING_DATASOURCE_URL` | Production PostgreSQL JDBC URL | `jdbc:postgresql://postgres.domain:5432/neurovault` |
| `SPRING_DATASOURCE_USERNAME` | Production DB user | `neurovault_admin` |
| `SPRING_DATASOURCE_PASSWORD` | Production DB password | Secret value |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | DDL schema management | `validate` (strict production) or `update` (dev) |
| `NEUROVAULT_STORAGE_BASE_DIR` | Server storage root | `/var/neurovault-storage` or `./neurovault-storage` |

---

## 2. Health Monitoring & Observability

### 2.1 Health Check Endpoints
- **Liveness & Health Probe:** `GET /health` or `GET /api/health`
  - Returns `200 OK` with service uptime and database connectivity.
- **Storage Host Probe:** `GET http://<host-ip>:8080/health`
  - Returns `200 OK` confirming host daemon is alive.

### 2.2 Logging Standards
- Format: Structured JSON logs with timestamps, log levels, request correlation IDs, and sanitized user identifiers.
- Sensitive Data Masking: Passwords, plaintext DEKs, raw JWT tokens, and decrypted chunk bytes are strictly excluded from logs.

---

## 3. Disaster Recovery & Node Self-Healing

1. **Host Node Failure:**
   - Host heartbeat threshold: 90 seconds (3 missed intervals).
   - If a host node stops responding, the Coordinator marks it `OFFLINE`.
   - `SelfHealingService` triggers a healing cycle, inspecting under-replicated chunks and persisting `ReplicationTask` records to restore target replication factor ($N \ge 2$).
2. **Chunk Corruption Recovery:**
   - When a client or node detects a CRC32/SHA-256 mismatch, `CorruptedChunkException` is thrown.
   - The coordinator marks the affected replica `CORRUPTED` and triggers immediate repair from surviving healthy replicas.
