import 'user_model.dart';

/// Response payload from registration endpoint.
class RegisterResponse {
  final String token;
  final String type;
  final UserModel user;

  const RegisterResponse({
    required this.token,
    required this.type,
    required this.user,
  });

  factory RegisterResponse.fromJson(Map<String, dynamic> json) {
    return RegisterResponse(
      token: json['token']?.toString() ?? '',
      type: json['type']?.toString() ?? 'Bearer',
      user: UserModel.fromJson(json['user'] ?? {}),
    );
  }
}
