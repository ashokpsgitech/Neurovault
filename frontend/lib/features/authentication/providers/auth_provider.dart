import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/errors/failures.dart';
import '../../../core/firebase/firebase_service.dart';
import '../../../providers/core_providers.dart';
import '../../files/providers/file_provider.dart';
import '../data/auth_repository.dart';
import 'auth_state.dart';

final authRepositoryProvider = Provider<AuthRepository>((ref) {
  final firebaseService = FirebaseService();
  final storageService = ref.watch(secureStorageProvider);
  return AuthRepository(firebaseService, storageService);
});

final authStateProvider = StateNotifierProvider<AuthNotifier, AuthState>((ref) {
  final authRepository = ref.watch(authRepositoryProvider);
  return AuthNotifier(authRepository, ref);
});

/// Riverpod StateNotifier managing authentication lifecycle.
class AuthNotifier extends StateNotifier<AuthState> {
  final AuthRepository _repository;
  final Ref _ref;

  AuthNotifier(this._repository, this._ref) : super(const AuthInitial());

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

      // Enforce email verification check for email/password users
      final currentFbUser = FirebaseService().currentUser;
      final isPasswordUser = currentFbUser?.providerData.any((p) => p.providerId == 'password') ?? false;

      if (isPasswordUser) {
        bool isVerified = false;
        try {
          isVerified = await _repository.checkEmailVerified();
        } catch (_) {
          // Network unavailable — use the cached value from the Firebase SDK
          isVerified = currentFbUser?.emailVerified ?? false;
        }
        if (!isVerified) {
          // Don't logout — just put them in Unauthenticated so they see the login screen
          // The Firebase session stays alive so resend-verification still works
          state = const Unauthenticated();
          return;
        }
      }

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
      _ref.read(fileProvider.notifier).loadFiles();
    } on Failure catch (f) {
      state = AuthError(f.message);
    } catch (e) {
      state = AuthError(e.toString());
    }
  }

  /// Registers user with username, email, password, role, and mode.
  Future<void> register(String username, String email, String password, {String role = 'CLIENT', String mode = 'PRIVATE'}) async {
    state = const AuthLoading();
    try {
      final response = await _repository.register(username, email, password);
      await _repository.updateUserPreferences(role: role, mode: mode);
      state = Authenticated(response.user.copyWith(role: role, mode: mode));
      _ref.read(fileProvider.notifier).loadFiles();
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
      _ref.read(fileProvider.notifier).loadFiles();
    } on Failure catch (f) {
      state = AuthError(f.message);
    } catch (e) {
      state = AuthError(e.toString());
    }
  }

  /// Authenticates user anonymously for Public Mode.
  Future<void> signInAnonymously() async {
    state = const AuthLoading();
    try {
      final response = await _repository.signInAnonymously();
      state = Authenticated(response.user);
      _ref.read(fileProvider.notifier).loadFiles();
    } on Failure catch (f) {
      state = AuthError(f.message);
    } catch (e) {
      state = AuthError(e.toString());
    }
  }

  /// Updates user role and mode preferences.
  Future<void> updateUserPreferences({required String role, required String mode}) async {
    if (state is Authenticated) {
      final currentUser = (state as Authenticated).user;
      final updatedUser = currentUser.copyWith(role: role, mode: mode);
      await _repository.updateUserPreferences(role: role, mode: mode);
      state = Authenticated(updatedUser);
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

  /// Logs out user and clears token and in-memory file state.
  Future<void> logout() async {
    _ref.read(fileProvider.notifier).clear();
    await _repository.logout();
    state = const Unauthenticated();
  }
}
