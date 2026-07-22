import 'user_model.dart';

/// Response payload from registration endpoint (UserDto).
class RegisterResponse {
  final UserModel user;

  const RegisterResponse({
    required this.user,
  });

  factory RegisterResponse.fromJson(Map<String, dynamic> json) {
    return RegisterResponse(
      user: UserModel.fromJson(json),
    );
  }
}
