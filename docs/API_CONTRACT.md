# NeuroVault REST API Contract & Specification

**Base URL:** `/api`  
**Authentication:** `Authorization: Bearer <jwt_token>` (for Coordinator endpoints)  
**Chunk Capabilities:** `X-Chunk-Token: <chunk_token>` (for direct Storage Host operations)  

---

## 1. Authentication Endpoints (`/api/auth`)

### 1.1 User Registration
- **Method:** `POST /api/auth/register`
- **Request Body:**
  ```json
  {
    "username": "alice",
    "email": "alice@example.com",
    "password": "StrongPassword123!"
  }
  ```
- **Response:** `201 Created`
  ```json
  {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "username": "alice",
    "email": "alice@example.com",
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
  ```

### 1.2 User Login
- **Method:** `POST /api/auth/login`
- **Request Body:**
  ```json
  {
    "username": "alice@example.com",
    "password": "StrongPassword123!"
  }
  ```
- **Response:** `200 OK`

---

## 2. File Metadata & Coordination (`/api/files`)

### 2.1 Request Upload Plan
- **Method:** `POST /api/files/upload-plan`
- **Headers:** `Authorization: Bearer <token>`
- **Request Body:**
  ```json
  {
    "filename": "dataset.tar.gz",
    "fileSize": 12582912,
    "totalChunks": 3,
    "checksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }
  ```
- **Response:** `201 Created`
  ```json
  {
    "uploadSessionId": "7b889b6c-2f47-4f8e-a226-e13d93bfbc32",
    "fileId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "filename": "dataset.tar.gz",
    "fileSize": 12582912,
    "totalChunks": 3,
    "chunkAllocations": [
      {
        "chunkId": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
        "chunkIndex": 0,
        "hostId": "550e8400-e29b-41d4-a716-446655440000",
        "hostName": "Node-Alpha",
        "publicIp": "192.168.1.100",
        "uploadUrl": "http://192.168.1.100:8080/api/storage/chunks",
        "chunkToken": "eyJhbGci...",
        "maxSizeBytes": 4194304
      }
    ],
    "expiresAt": "2026-09-23T22:30:00"
  }
  ```

### 2.2 Complete Upload Session
- **Method:** `POST /api/files/upload-complete`
- **Headers:** `Authorization: Bearer <token>`
- **Request Body:**
  ```json
  {
    "uploadSessionId": "7b889b6c-2f47-4f8e-a226-e13d93bfbc32",
    "encryptedAesKey": "12ByteNonceBase64+Ciphertext+TagBase64...",
    "fileHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "uploadedChunks": [
      {
        "chunkId": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
        "chunkIndex": 0,
        "hostId": "550e8400-e29b-41d4-a716-446655440000",
        "chunkHash": "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3",
        "sizeBytes": 4194304
      }
    ]
  }
  ```
- **Response:** `200 OK`

### 2.3 Request Download Plan
- **Method:** `GET /api/files/download-plan/{fileId}`
- **Headers:** `Authorization: Bearer <token>`
- **Response:** `200 OK` with chunk locations, download URLs, and scoped download tokens.

---

## 3. Storage Host Node Endpoints (`/api/storage`)

Direct interaction with storage host nodes (via HTTP or WebRTC DataChannel) enforces fine-grained capability token authorization.

### 3.1 Scoped Capability Token Claims
```json
{
  "sub": "user@example.com",
  "sessionId": "7b889b6c-2f47-4f8e-a226-e13d93bfbc32",
  "hostId": "550e8400-e29b-41d4-a716-446655440000",
  "chunkId": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
  "operation": "WRITE",
  "exp": 1758654000
}
```

### 3.2 Store Chunk
- **Method:** `POST /api/storage/chunks`
- **Headers:** `X-Chunk-Token: <token>` (must have claim `operation: WRITE` or `REPLICATE` and matching `chunkId` and `hostId`)
- **Request Body:**
  ```json
  {
    "chunkId": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
    "data": [1, 2, 3, 4]
  }
  ```
- **Response:** `201 Created`

### 3.3 Read Chunk
- **Method:** `GET /api/storage/chunks/{chunkId}`
- **Headers:** `X-Chunk-Token: <token>` (must have claim `operation: READ` and matching `chunkId`)
- **Response:** `200 OK` (binary chunk stream)

---

## 4. WebRTC Peer-to-Peer DataChannel Protocol

When transferring chunks across NAT/firewalls between clients and remote hosts, WebRTC DataChannels are utilized via Firestore signaling (`signaling/{hostId}/sessions/{sessionId}`).

### 4.1 Handshake Messages
- **Upload Handshake:** Client sends `$chunkId|$chunkSizeBytes|$capabilityToken`
  - Host validates capability token with operation `WRITE` before accepting binary frames.
  - Slices are transmitted in bounded 64KB binary messages.
  - End of transfer is signalled with `__EOF__`.
  - Host responds with `ACK:$chunkId` or `ERR:forbidden:<reason>`.
- **Download Handshake:** Client sends `GET|$chunkId|$capabilityToken`
  - Host validates capability token with operation `READ` before streaming bytes.
  - Slices are streamed in bounded 64KB frames, terminated with `__EOF__`.

---

## 5. Standard Error Structure

```json
{
  "timestamp": "2026-09-23T21:30:00",
  "status": 403,
  "error": "Forbidden",
  "message": "Access denied: Capability token does not grant WRITE permission on chunk 1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d"
}
```
