import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/errors/failures.dart';
import '../../../core/firebase/firebase_service.dart';
import '../../../providers/core_providers.dart';
import '../data/auth_repository.dart';
import 'auth_state.dart';

final authRepositoryProvider = Provider<AuthRepository>((ref) {
  final firebaseService = FirebaseService();
  final storageService = ref.watch(secureStorageProvider);
  return AuthRepository(firebaseService, storageService);
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

  /// Authenticates user using Google Sign-In.
  Future<void> signInWithGoogle() async {
    state = const AuthLoading();
    try {
      final response = await _repository.signInWithGoogle();
      state = Authenticated(response.user);
    } on Failure catch (f) {
      state = AuthError(f.message);
    } catch (e) {
      state = AuthError(e.toString());
    }
  }

  /// Checks if current user's email has been verified.
  Future<bool> checkEmailVerified() async {
    return await _repository.checkEmailVerified();
  }

  /// Resends email verification code/link.
  Future<void> resendEmailVerification() async {
    await _repository.resendEmailVerification();
  }

  /// Logs out user and clears token.
  Future<void> logout() async {
    await _repository.logout();
    state = const Unauthenticated();
  }
}
