import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../constants/app_constants.dart';

/// Service wrapping flutter_secure_storage for secure token and settings persistence.
class SecureStorageService {
  final FlutterSecureStorage _storage;

  SecureStorageService({FlutterSecureStorage? storage})
      : _storage = storage ??
            const FlutterSecureStorage(
              aOptions: AndroidOptions(encryptedSharedPreferences: true),
            );

  Future<void> saveToken(String token) async {
    await _storage.write(key: AppConstants.keyJwtToken, value: token);
  }

  Future<String?> getToken() async {
    return await _storage.read(key: AppConstants.keyJwtToken);
  }

  Future<void> deleteToken() async {
    await _storage.delete(key: AppConstants.keyJwtToken);
  }

  Future<void> saveUserEmail(String email) async {
    await _storage.write(key: AppConstants.keyUserEmail, value: email);
  }

  Future<String?> getUserEmail() async {
    return await _storage.read(key: AppConstants.keyUserEmail);
  }

  Future<void> saveHostContainerPath(String path) async {
    await _storage.write(key: 'host_container_path', value: path);
  }

  Future<String?> getHostContainerPath() async {
    return await _storage.read(key: 'host_container_path');
  }

  Future<void> saveHostContainerSizeGb(int sizeGb) async {
    await _storage.write(key: 'host_container_size_gb', value: sizeGb.toString());
  }

  Future<int?> getHostContainerSizeGb() async {
    final str = await _storage.read(key: 'host_container_size_gb');
    if (str != null) return int.tryParse(str);
    return null;
  }

  Future<void> saveHostAllocated(bool isAllocated) async {
    await _storage.write(key: 'host_is_allocated', value: isAllocated ? 'true' : 'false');
  }

  Future<bool> isHostAllocated() async {
    final val = await _storage.read(key: 'host_is_allocated');
    return val == 'true';
  }

  Future<void> clearAll() async {
    await _storage.deleteAll();
  }
}
