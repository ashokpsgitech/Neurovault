import 'user_model.dart';

/// Response payload from auth endpoints matching backend AuthResponse.
class LoginResponse {
  final String token;
  final String type;
  final UserModel user;

  const LoginResponse({
    required this.token,
    this.type = 'Bearer',
    required this.user,
  });

  factory LoginResponse.fromJson(Map<String, dynamic> json) {
    return LoginResponse(
      token: json['token']?.toString() ?? '',
      type: json['type']?.toString() ?? 'Bearer',
      user: UserModel.fromJson(json['user'] ?? {}),
    );
  }
}
