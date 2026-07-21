/// Environment configuration for NeuroVault Flutter Client.
class EnvConfig {
  static const String coordinatorBaseUrl = String.fromEnvironment(
    'COORDINATOR_URL',
    defaultValue: 'http://localhost:8080',
  );

  static const int connectTimeoutMs = 15000;
  static const int receiveTimeoutMs = 15000;
}
