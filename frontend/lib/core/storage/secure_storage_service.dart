import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../constants/app_constants.dart';

/// Service wrapping flutter_secure_storage for secure token and settings persistence.
class SecureStorageService {
  final FlutterSecureStorage _storage;

  SecureStorageService({FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage();

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

  Future<void> clearAll() async {
    await _storage.deleteAll();
  }
}
