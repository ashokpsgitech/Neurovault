import 'package:flutter/foundation.dart';

/// Environment configuration for NeuroVault Flutter Client.
class EnvConfig {
  static String get coordinatorBaseUrl {
    const fromEnv = String.fromEnvironment('COORDINATOR_URL');
    if (fromEnv.isNotEmpty) return fromEnv;

    if (kIsWeb) {
      final host = Uri.base.host;
      if (host.isNotEmpty && host != 'localhost' && host != '127.0.0.1') {
        return 'http://$host:8080';
      }
      return 'http://10.42.96.100:8080';
    }

    if (defaultTargetPlatform == TargetPlatform.android) {
      return 'http://10.42.96.100:8080';
    }

    return 'http://localhost:8080';
  }

  static const int connectTimeoutMs = 15000;
  static const int receiveTimeoutMs = 15000;
}
