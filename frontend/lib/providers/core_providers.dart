import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/config/env_config.dart';
import '../core/network/dio_client.dart';
import '../core/storage/secure_storage_service.dart';

final secureStorageProvider = Provider<SecureStorageService>((ref) {
  return SecureStorageService();
});

final dioClientProvider = Provider<DioClient>((ref) {
  final storage = ref.watch(secureStorageProvider);
  return DioClient(
    baseUrl: EnvConfig.coordinatorBaseUrl,
    getToken: () => storage.getToken(),
  );
});

final themeModeProvider = StateProvider<ThemeMode>((ref) {
  return ThemeMode.dark;
});
