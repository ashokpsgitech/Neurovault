import 'package:flutter/foundation.dart';

/// Environment configuration for NeuroVault Flutter Client.
class EnvConfig {
  static String get coordinatorBaseUrl {
    const fromEnv = String.fromEnvironment('COORDINATOR_URL');
    if (fromEnv.isNotEmpty) return fromEnv;

    if (kIsWeb) {
      return 'http://localhost:8080';
    }

    if (defaultTargetPlatform == TargetPlatform.android) {
      // Your PC's local Wi-Fi IPv4 address for physical Android testing
      return 'http://10.10.193.106:8080';
    }

    return 'http://localhost:8080';
  }

  static const int connectTimeoutMs = 15000;
  static const int receiveTimeoutMs = 15000;
}
