-- =============================================================================
-- NeuroVault V1 Initial Schema
-- Production-grade schema with foreign keys, unique constraints, check constraints,
-- and query optimization indexes.
-- =============================================================================

-- 1. USERS
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    mode VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    is_anonymous BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 2. HOSTS
CREATE TABLE IF NOT EXISTS hosts (
    id UUID PRIMARY KEY,
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    name VARCHAR(100) NOT NULL,
    device_type VARCHAR(50),
    operating_system VARCHAR(50),
    public_ip VARCHAR(45),
    total_capacity_bytes BIGINT NOT NULL,
    reserved_capacity_bytes BIGINT NOT NULL DEFAULT 0,
    used_capacity_bytes BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    mode VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    is_anonymous BOOLEAN NOT NULL DEFAULT FALSE,
    last_heartbeat TIMESTAMP,
    heartbeat_interval_seconds INT NOT NULL DEFAULT 30,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_host_capacity CHECK (
        used_capacity_bytes >= 0 AND 
        reserved_capacity_bytes >= 0 AND 
        total_capacity_bytes >= (used_capacity_bytes + reserved_capacity_bytes)
    )
);

CREATE INDEX IF NOT EXISTS idx_hosts_status ON hosts(status);
CREATE INDEX IF NOT EXISTS idx_hosts_owner ON hosts(owner_id);

-- 3. STORAGE CONTAINERS
CREATE TABLE IF NOT EXISTS storage_containers (
    id UUID PRIMARY KEY,
    host_id UUID NOT NULL UNIQUE REFERENCES hosts(id) ON DELETE CASCADE,
    file_path VARCHAR(255) NOT NULL,
    total_size BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 4. FILES
CREATE TABLE IF NOT EXISTS files (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    path VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    mime_type VARCHAR(100),
    encrypted_aes_key TEXT NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_files_owner ON files(owner_id);

-- 5. CHUNKS
CREATE TABLE IF NOT EXISTS chunks (
    id UUID PRIMARY KEY,
    file_id UUID NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    CONSTRAINT uk_chunk_file_index UNIQUE (file_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_chunks_file ON chunks(file_id);
CREATE INDEX IF NOT EXISTS idx_chunks_status ON chunks(status);

-- 6. CHUNK REPLICAS
CREATE TABLE IF NOT EXISTS chunk_replicas (
    id UUID PRIMARY KEY,
    chunk_id UUID NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    host_id UUID NOT NULL REFERENCES hosts(id) ON DELETE CASCADE,
    container_offset_bytes BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_replica_chunk_host UNIQUE (chunk_id, host_id)
);

CREATE INDEX IF NOT EXISTS idx_replicas_chunk ON chunk_replicas(chunk_id);
CREATE INDEX IF NOT EXISTS idx_replicas_host ON chunk_replicas(host_id);
CREATE INDEX IF NOT EXISTS idx_replicas_status ON chunk_replicas(status);

-- 7. REPLICATION TASKS
CREATE TABLE IF NOT EXISTS replication_tasks (
    id UUID PRIMARY KEY,
    chunk_id UUID NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    source_host_id UUID REFERENCES hosts(id) ON DELETE SET NULL,
    target_host_id UUID NOT NULL REFERENCES hosts(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(255),
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    worker_id VARCHAR(100),
    max_attempts INT NOT NULL DEFAULT 3,
    backoff_seconds INT NOT NULL DEFAULT 30,
    lease_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_repl_task_status ON replication_tasks(status);
CREATE INDEX IF NOT EXISTS idx_repl_task_chunk ON replication_tasks(chunk_id);
CREATE INDEX IF NOT EXISTS idx_repl_task_lease ON replication_tasks(status, lease_expires_at);

-- 8. HOST HEARTBEATS
CREATE TABLE IF NOT EXISTS host_heartbeats (
    id UUID PRIMARY KEY,
    host_id UUID NOT NULL REFERENCES hosts(id) ON DELETE CASCADE,
    timestamp TIMESTAMP NOT NULL,
    free_space_bytes BIGINT,
    cpu_usage_percent DOUBLE PRECISION,
    memory_usage_percent DOUBLE PRECISION
);

CREATE INDEX IF NOT EXISTS idx_heartbeats_host ON host_heartbeats(host_id, timestamp);

-- 9. AUDIT LOGS
CREATE TABLE IF NOT EXISTS audit_logs (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(50) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id VARCHAR(100),
    details TEXT,
    ip_address VARCHAR(45),
    timestamp TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_logs(timestamp);
