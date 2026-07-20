# Host Module — NeuroVault

## Overview

The Host module handles the registration and lifecycle of storage nodes (micro-servers) in the NeuroVault distributed storage network. It provides REST APIs for host registration and heartbeat processing, and includes a scheduled monitor that detects stale hosts.

## Architecture

```
host/
├── config/
│   └── HostConfig.java              # Enables @Scheduled
├── controller/
│   └── HostController.java          # REST endpoints
├── dto/
│   ├── HeartbeatRequest.java        # Heartbeat payload
│   ├── HeartbeatResponse.java       # Heartbeat acknowledgment
│   ├── HostRegistrationRequest.java # Registration payload
│   ├── HostRegistrationResponse.java# Registration confirmation
│   └── HostStatusDto.java           # Host status details
├── scheduler/
│   └── HeartbeatScheduler.java      # Stale host detection
└── service/
    ├── HeartbeatService.java        # Heartbeat processing
    └── HostRegistrationService.java # Host registration
```

## API Endpoints

### POST `/api/hosts/register`

Registers a new host device.

**Request Body:**
```json
{
  "hostname": "my-laptop",
  "deviceName": "Dell XPS 15",
  "operatingSystem": "Windows 11",
  "architecture": "x86_64",
  "availableStorageBytes": 100000000000,
  "reservedStorageBytes": 10000000000,
  "hostVersion": "1.0.0",
  "listeningPort": 8081,
  "publicIp": "203.0.113.5",
  "localIp": "192.168.1.100"
}
```

**Response (201):**
```json
{
  "hostId": "uuid",
  "registrationStatus": "REGISTERED",
  "heartbeatIntervalSeconds": 30,
  "registeredAt": "2026-07-20T15:30:00"
}
```

### POST `/api/hosts/{hostId}/heartbeat`

Receives a heartbeat from a host.

**Request Body:**
```json
{
  "hostId": "uuid",
  "timestamp": "2026-07-20T15:30:00",
  "cpuUsagePercent": 45.5,
  "ramUsagePercent": 67.2,
  "reservedStorageBytes": 5000000000,
  "usedStorageBytes": 2000000000,
  "availableStorageBytes": 3000000000,
  "hostStatus": "ONLINE",
  "containerStatus": "ACTIVE"
}
```

**Response (200):**
```json
{
  "acknowledged": true,
  "nextHeartbeatSeconds": 30,
  "serverTimestamp": "2026-07-20T15:30:00"
}
```

### GET `/api/hosts/{hostId}`

Returns host details.

### GET `/api/hosts`

Lists all hosts for the authenticated user.

## Heartbeat System

- Hosts send heartbeats every **30 seconds** (configurable per host).
- The `HeartbeatScheduler` runs every **60 seconds** (configurable via `neurovault.host.heartbeat-check-interval-ms`).
- A host is marked **OFFLINE** if it misses **3×** its heartbeat interval (configurable via `neurovault.host.heartbeat-miss-threshold`).

## Testing

```bash
./gradlew test --tests "com.neurovault.backend.host.*"
```

## Dependencies

This module depends on the existing (read-only) components:
- `Host` entity
- `HostHeartbeat` entity
- `HostRepository`
- `HostHeartbeatRepository`
- `UserRepository`
- `ResourceNotFoundException`
