import 'dart:io';
import 'package:flutter/services.dart';

/// Flutter MethodChannel bridge to Android HostForegroundService.
class HostBackgroundService {
  static const MethodChannel _channel = MethodChannel('neurovault/host_service');

  /// Starts Android 24/7 Foreground Service for Host Node.
  static Future<bool> startHostService({
    required int reservedGb,
    required String containerPath,
  }) async {
    if (!Platform.isAndroid) return true;
    try {
      final result = await _channel.invokeMethod<bool>('startHostService', {
        'reservedGb': reservedGb,
        'containerPath': containerPath,
      });
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Stops Android Foreground Service.
  static Future<bool> stopHostService() async {
    if (!Platform.isAndroid) return true;
    try {
      final result = await _channel.invokeMethod<bool>('stopHostService');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Checks if Android Foreground Service is currently running.
  static Future<bool> isServiceRunning() async {
    if (!Platform.isAndroid) return true;
    try {
      final result = await _channel.invokeMethod<bool>('isServiceRunning');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }
}
