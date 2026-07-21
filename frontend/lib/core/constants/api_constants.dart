/// REST API Endpoints matching the Spring Boot Coordinator backend.
class ApiConstants {
  // Auth Endpoints
  static const String register = '/api/auth/register';
  static const String login = '/api/auth/login';
  static const String currentUser = '/api/auth/me';

  // Host Endpoints
  static const String registerHost = '/api/host/register';
  static const String heartbeat = '/api/host/heartbeat';
  static const String hostStatus = '/api/host/status';

  // Storage Endpoints
  static const String createStorage = '/api/storage/create';
  static const String storageStatus = '/api/storage/status';
  static const String storeChunk = '/api/storage/chunks';
  static const String readChunk = '/api/storage/chunks/';

  // File Pipeline Coordination Endpoints (Metadata-Only)
  static const String uploadPlan = '/api/files/upload-plan';
  static const String uploadComplete = '/api/files/upload-complete';
  static const String downloadPlan = '/api/files/download-plan/';
  static const String uploadProgress = '/api/files/progress/';
}
