/// Request payload for register endpoint POST /api/auth/register.
class RegisterRequest {
  final String username;
  final String email;
  final String password;
  final String role;

  const RegisterRequest({
    required this.username,
    required this.email,
    required this.password,
    this.role = 'CLIENT',
  });

  Map<String, dynamic> toJson() {
    return {
      'username': username,
      'email': email,
      'password': password,
      'role': role,
    };
  }
}
