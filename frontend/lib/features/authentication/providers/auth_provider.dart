import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/errors/failures.dart';
import '../../../providers/core_providers.dart';
import '../data/auth_repository.dart';
import '../services/auth_service.dart';
import 'auth_state.dart';

final authServiceProvider = Provider<AuthService>((ref) {
  final dioClient = ref.watch(dioClientProvider);
  return AuthService(dioClient);
});

final authRepositoryProvider = Provider<AuthRepository>((ref) {
  final authService = ref.watch(authServiceProvider);
  final storageService = ref.watch(secureStorageProvider);
  return AuthRepository(authService, storageService);
});

final authStateProvider = StateNotifierProvider<AuthNotifier, AuthState>((ref) {
  final authRepository = ref.watch(authRepositoryProvider);
  return AuthNotifier(authRepository);
});

/// Riverpod StateNotifier managing authentication lifecycle.
class AuthNotifier extends StateNotifier<AuthState> {
  final AuthRepository _repository;

  AuthNotifier(this._repository) : super(const AuthInitial());

  /// Checks secure storage and validates session on startup.
  Future<void> checkAuthStatus() async {
    state = const AuthLoading();
    try {
      final hasToken = await _repository.hasStoredToken();
      if (!hasToken) {
        state = const Unauthenticated();
        return;
      }
      final user = await _repository.getCurrentUser();
      state = Authenticated(user);
    } catch (e) {
      await _repository.logout();
      state = const Unauthenticated();
    }
  }

  /// Logs in user with email and password.
  Future<void> login(String email, String password) async {
    state = const AuthLoading();
    try {
      final response = await _repository.login(email, password);
      state = Authenticated(response.user);
    } on Failure catch (f) {
      state = AuthError(f.message);
    } catch (e) {
      state = AuthError(e.toString());
    }
  }

  /// Registers user with username, email, and password.
  Future<void> register(String username, String email, String password) async {
    state = const AuthLoading();
    try {
      final response = await _repository.register(username, email, password);
      state = Authenticated(response.user);
    } on Failure catch (f) {
      state = AuthError(f.message);
    } catch (e) {
      state = AuthError(e.toString());
    }
  }

  /// Logs out user and clears token.
  Future<void> logout() async {
    await _repository.logout();
    state = const Unauthenticated();
  }
}
