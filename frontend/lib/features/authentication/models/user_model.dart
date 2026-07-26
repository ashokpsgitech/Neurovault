/// User model representation matching backend UserDto contract.
class UserModel {
  final String id;
  final String username;
  final String email;
  final String role; // 'CLIENT', 'HOST', 'BOTH'
  final String mode; // 'PRIVATE', 'PUBLIC'
  final bool isAnonymous;

  const UserModel({
    required this.id,
    required this.username,
    required this.email,
    required this.role,
    this.mode = 'PRIVATE',
    this.isAnonymous = false,
  });

  factory UserModel.fromJson(Map<String, dynamic> json) {
    return UserModel(
      id: json['id']?.toString() ?? '',
      username: json['username']?.toString() ?? '',
      email: json['email']?.toString() ?? '',
      role: json['role']?.toString() ?? 'CLIENT',
      mode: json['mode']?.toString() ?? 'PRIVATE',
      isAnonymous: json['isAnonymous'] == true,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'username': username,
      'email': email,
      'role': role,
      'mode': mode,
      'isAnonymous': isAnonymous,
    };
  }

  UserModel copyWith({
    String? id,
    String? username,
    String? email,
    String? role,
    String? mode,
    bool? isAnonymous,
  }) {
    return UserModel(
      id: id ?? this.id,
      username: username ?? this.username,
      email: email ?? this.email,
      role: role ?? this.role,
      mode: mode ?? this.mode,
      isAnonymous: isAnonymous ?? this.isAnonymous,
    );
  }
}
