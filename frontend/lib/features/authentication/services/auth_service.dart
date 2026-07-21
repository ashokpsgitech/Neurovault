import '../../../core/constants/api_constants.dart';
import '../../../core/network/dio_client.dart';
import '../models/login_request.dart';
import '../models/login_response.dart';
import '../models/register_request.dart';
import '../models/register_response.dart';
import '../models/user_model.dart';

/// Network service performing REST HTTP calls to Spring Boot Coordinator /api/auth/* endpoints.
class AuthService {
  final DioClient _dioClient;

  AuthService(this._dioClient);

  /// POST /api/auth/login
  Future<LoginResponse> login(LoginRequest request) async {
    final response = await _dioClient.dio.post(
      ApiConstants.login,
      data: request.toJson(),
    );
    return LoginResponse.fromJson(response.data);
  }

  /// POST /api/auth/register
  Future<RegisterResponse> register(RegisterRequest request) async {
    final response = await _dioClient.dio.post(
      ApiConstants.register,
      data: request.toJson(),
    );
    return RegisterResponse.fromJson(response.data);
  }

  /// GET /api/auth/me
  Future<UserModel> getCurrentUser() async {
    final response = await _dioClient.dio.get(
      ApiConstants.currentUser,
    );
    return UserModel.fromJson(response.data);
  }
}
