# Storage Module — NeuroVault

## Overview

The Storage module manages the binary container files and encrypted chunk storage on host devices. It provides a complete storage engine with container lifecycle management, chunk CRUD operations, integrity verification, and REST APIs.

## Architecture

```
storage/
├── config/
│   ├── StorageConfig.java            # Enables ConfigurationProperties
│   └── StorageProperties.java        # neurovault.storage.* config
├── container/
│   └── ContainerManager.java         # Binary container I/O (Java NIO)
├── controller/
│   └── StorageController.java        # REST endpoints
├── dto/
│   ├── ChunkMetadataDto.java         # Chunk metadata API response
│   ├── CreateContainerRequest.java   # Container creation request
│   ├── StorageStatusResponse.java    # Storage status response
│   └── StoreChunkRequest.java        # Chunk store request
├── engine/
│   └── StorageEngine.java            # High-level chunk operations
├── exception/
│   ├── ChunkNotFoundException.java   # Missing chunk
│   ├── ContainerException.java       # Container I/O errors
│   ├── StorageExceptionHandler.java  # REST error mapping
│   └── StorageFullException.java     # Capacity exhaustion
└── model/
    ├── ChunkMetadata.java            # Chunk metadata POJO
    ├── ContainerHeader.java          # 256-byte binary header
    └── StorageReservationSize.java   # Allowed sizes enum
```

## Container Binary Format

The `storage.container` file is a single binary file visible to the OS. Client files are never directly visible.

### Layout

```
[Header: 256B] [Metadata Index: 1MB] [Data Region: remaining]
```

### Header (256 bytes)

| Offset | Size | Field                    |
|--------|------|--------------------------|
| 0      | 4    | Magic bytes (`NVLT`)     |
| 4      | 4    | Version                  |
| 8      | 8    | Total container size     |
| 16     | 8    | Used data size           |
| 24     | 4    | Chunk count              |
| 28     | 8    | Metadata region offset   |
| 36     | 8    | Metadata region size     |
| 44     | 8    | Data region offset       |
| 52     | 8    | Created timestamp        |
| 60     | 8    | Last modified timestamp  |
| 68     | 188  | Reserved (zero-padded)   |

### Metadata Region

Contains a serialized Java `ArrayList<ChunkMetadata>` with a 4-byte length prefix.

### Data Region

Contiguous encrypted chunk data, append-only. Deleted chunks are soft-deleted and space is tracked but not immediately reclaimed.

## Supported Reservation Sizes

| Name    | Bytes          |
|---------|----------------|
| MB_500  | 524,288,000    |
| GB_1    | 1,073,741,824  |
| GB_2    | 2,147,483,648  |
| GB_5    | 5,368,709,120  |
| GB_10   | 10,737,418,240 |
| GB_20   | 21,474,836,480 |

## API Endpoints

### GET `/api/storage/status?hostId=`

Returns storage status.

**Response (200):**
```json
{
  "containerSizeBytes": 1073741824,
  "usedSpaceBytes": 5242880,
  "freeSpaceBytes": 1068498944,
  "chunkCount": 12,
  "hostStatus": "ONLINE",
  "containerStatus": "ACTIVE"
}
```

### POST `/api/storage/create`

Creates a storage container.

**Request Body:**
```json
{
  "hostId": "uuid",
  "reservationSize": "GB_1"
}
```

### DELETE `/api/storage/delete?hostId=`

Deletes a storage container.

### GET `/api/storage/chunks?hostId=`

Lists all stored chunk metadata.

### POST `/api/storage/chunks?hostId=`

Stores an encrypted chunk.

**Request Body:**
```json
{
  "chunkId": "uuid",
  "ownerId": "uuid",
  "data": "<base64-encoded-bytes>"
}
```

### GET `/api/storage/chunks/{chunkId}?hostId=`

Reads encrypted chunk bytes.

### DELETE `/api/storage/chunks/{chunkId}?hostId=`

Deletes a chunk.

## Storage Engine Design

The `StorageEngine` maintains an in-memory `ConcurrentHashMap<UUID, ChunkMetadata>` index. On every write:

1. Compute SHA-256 hash and CRC32 checksum
2. Find the next free offset in the data region
3. Write encrypted bytes via `ContainerManager`
4. Update the in-memory index
5. Persist the index to the metadata region
6. Update the container header

On every read, the CRC32 checksum is verified to detect corruption.

## Configuration

```yaml
neurovault:
  storage:
    base-dir: ./neurovault-storage
```

Each host gets a subdirectory: `{base-dir}/{hostId}/storage.container`

## Testing

```bash
./gradlew test --tests "com.neurovault.backend.storage.*"
```

## Dependencies

This module depends on the existing (read-only) components:
- `Host` entity
- `StorageContainer` entity
- `HostRepository`
- `StorageContainerRepository`
- `ErrorResponse` DTO
- `BadRequestException`, `ResourceNotFoundException`
