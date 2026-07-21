# 🧠 Neurovault - Distributed Micro-Server File Vault System

**Neurovault** is a high-performance, fault-tolerant, distributed file storage and chunking system backend built with Java 21 and Spring Boot 3.

---

## 🚀 Key Features & Architecture

Neurovault decouples file management into a high-availability micro-server topology:

- **🔐 Stateless JWT Authentication**: Spring Security 6 integration with role-based access control (RBAC) and HMAC-SHA256 token verification.
- **📦 Distributed File Chunking**: Large files are split into indexed chunks (`FileMetadata`, `Chunk`) with hash integrity verification.
- **🔄 Chunk Replication & Fault Tolerance**: Chunks are replicated across multiple node targets (`ChunkReplica`) for resilience.
- **🖥️ Host Node Fleet Management**: Real-time heartbeat tracking (`Host`, `HostHeartbeat`) for available storage capacities and node state monitoring.
- **🗄️ Containerized Storage Units**: Physical/virtual storage abstraction layer (`StorageContainer`).
- **⏳ Session Lifecycle Tracking**: Resumable multi-chunk upload (`UploadSession`) and tokenized secure download (`DownloadSession`) lifecycles.
- **📜 System Audit Logging**: Event logging for user operations and storage node management (`AuditLog`).

---

## 🛠️ Technology Stack

| Component | Technology |
| :--- | :--- |
| **Language** | Java 21 (LTS) |
| **Framework** | Spring Boot 3.3.1 |
| **Security** | Spring Security 6, JJWT (io.jsonwebtoken 0.12.6) |
| **Persistence** | Spring Data JPA (Hibernate 6) |
| **Database** | PostgreSQL (Production) / H2 (Testing) |
| **Build Tool** | Gradle |
| **Utilities** | Lombok, Jakarta Validation |

---

## 📁 Repository Structure

```
.
├── backend/
│   ├── build.gradle               # Dependency configurations & Gradle plugins
│   ├── settings.gradle            # Project settings
│   └── src/
│       ├── main/
│       │   ├── java/com/neurovault/backend/
│       │   │   ├── config/        # Security & application beans
│       │   │   ├── controller/    # REST API endpoints (e.g., AuthController)
│       │   │   ├── dto/           # Data Transfer Objects (Requests/Responses)
│       │   │   ├── entity/        # JPA Domain Entities
│       │   │   ├── exception/     # Global exception handling & custom errors
│       │   │   ├── repository/    # Spring Data JPA Repositories
│       │   │   ├── security/      # JWT filter, UserDetailsService & Utils
│       │   │   └── service/       # Business logic layer
│       │   └── resources/
│       │       └── application.yml # Environment and database configuration
│       └── test/                  # Unit and integration test suite
└── DOCUMENTATION.md           # Deep-dive architecture and design guide
└── README.md
```

---

## 📡 API Reference Summary

### Authentication (`/api/auth` or `/api/v1/auth`)

| Method | Endpoint | Description | Auth Required |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/auth/register` | Register a new user | ❌ No |
| `POST` | `/api/v1/auth/login` | Authenticate user and receive JWT token | ❌ No |
| `GET` | `/api/v1/auth/me` | Fetch authenticated user details | ✅ Yes (Bearer Token) |

### Cluster Management & Monitoring (`/api/cluster`)

| Method | Endpoint | Description | Auth Required |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/cluster/status` | Fetch cluster summary statistics | ✅ Yes |
| `GET` | `/api/cluster/hosts` | List status and health of all hosts | ✅ Yes |
| `GET` | `/api/cluster/replicas` | Fetch placement info for all chunk replicas | ✅ Yes |
| `POST` | `/api/cluster/repair` | Manually trigger self-healing recovery scan | ✅ Yes |
| `GET` | `/api/cluster/health` | Get overall cluster health level and active issues | ✅ Yes |

For details on self-healing workflows, replication algorithms, and load-balancing strategies, check [DOCUMENTATION.md](file:///c:/Users/Sri%20Ashwin/OneDrive/Desktop/neurovault/Neurovault/DOCUMENTATION.md).

---

## ⚙️ Getting Started & Setup

### Prerequisites

- **Java JDK 21** installed and configured in system path.
- **PostgreSQL 14+** running locally or via Docker.
- Database `neurovault` created on host (`jdbc:postgresql://localhost:5432/neurovault`).

### Configuration

Database and JWT settings are located in `backend/src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/neurovault
    username: postgres
    password: your_password

app:
  jwt:
    secret: YOUR_SECURE_256_BIT_SECRET_KEY
    expiration-ms: 86400000 # 24 Hours
```

### Build & Run

1. **Navigate to backend**:
   ```bash
   cd backend
   ```

2. **Run tests**:
   ```bash
   ./gradlew test
   ```

3. **Start backend application**:
   ```bash
   ./gradlew bootRun
   ```

---

## 📄 License

This project is open-source software under the standard project terms.
