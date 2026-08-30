import 'package:flutter/foundation.dart';

/// Environment configuration for NeuroVault Flutter Client.
class EnvConfig {
  static String customUrl = '';

  static String get coordinatorBaseUrl {
    if (customUrl.isNotEmpty) return customUrl;
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
      return 'https://neurovault-coordinator-a0fxbegtcudkc4g8.centralindia-01.azurewebsites.net';
    }

    return 'https://neurovault-coordinator-a0fxbegtcudkc4g8.centralindia-01.azurewebsites.net';
  }

  static const int connectTimeoutMs = 15000;
  static const int receiveTimeoutMs = 15000;
}
