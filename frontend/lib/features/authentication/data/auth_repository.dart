import '../../../core/storage/secure_storage_service.dart';
import '../../../repositories/base_repository.dart';
import '../models/login_request.dart';
import '../models/login_response.dart';
import '../models/register_request.dart';
import '../models/user_model.dart';
import '../services/auth_service.dart';

/// Repository wrapping AuthService calls with exception handling and JWT secure storage management.
class AuthRepository extends BaseRepository {
  final AuthService _authService;
  final SecureStorageService _storageService;

  AuthRepository(this._authService, this._storageService);

  /// Authenticates user and saves JWT token to secure storage.
  Future<LoginResponse> login(String email, String password) async {
    return safeApiCall(() async {
      final response = await _authService.login(
        LoginRequest(email: email, password: password),
      );
      await _storageService.saveToken(response.token);
      await _storageService.saveUserEmail(response.user.email);
      return response;
    });
  }

  /// Registers user and logs in to save JWT token to secure storage.
  Future<LoginResponse> register(String username, String email, String password) async {
    return safeApiCall(() async {
      await _authService.register(
        RegisterRequest(username: username, email: email, password: password),
      );
      // Auto-login to obtain JWT token
      final loginResponse = await _authService.login(
        LoginRequest(email: email, password: password),
      );
      await _storageService.saveToken(loginResponse.token);
      await _storageService.saveUserEmail(loginResponse.user.email);
      return loginResponse;
    });
  }

  /// Restores session using stored JWT token.
  Future<UserModel> getCurrentUser() async {
    return safeApiCall(() async {
      return await _authService.getCurrentUser();
    });
  }

  /// Clears JWT token and session data from secure storage.
  Future<void> logout() async {
    await _storageService.clearAll();
  }

  /// Checks if a JWT token is stored locally.
  Future<bool> hasStoredToken() async {
    final token = await _storageService.getToken();
    return token != null && token.isNotEmpty;
  }
}
