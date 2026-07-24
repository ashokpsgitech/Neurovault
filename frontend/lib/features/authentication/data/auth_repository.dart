import 'package:firebase_auth/firebase_auth.dart';
import '../../../core/errors/failures.dart';
import '../../../core/firebase/firebase_service.dart';
import '../../../core/storage/secure_storage_service.dart';
import '../../../repositories/base_repository.dart';
import '../models/login_response.dart';
import '../models/user_model.dart';

/// Repository wrapping Firebase Authentication & Cloud Firestore user details.
class AuthRepository extends BaseRepository {
  final FirebaseService _firebaseService;
  final SecureStorageService _storageService;

  AuthRepository(this._firebaseService, this._storageService);

  /// Authenticates user via Firebase Auth and persists session token.
  Future<LoginResponse> login(String email, String password) async {
    try {
      final user = await _firebaseService.login(email: email, password: password);
      await _storageService.saveToken(user.id);
      await _storageService.saveUserEmail(user.email);
      return LoginResponse(token: user.id, type: 'Bearer', user: user);
    } on FirebaseAuthException catch (e) {
      throw AuthFailure(_mapFirebaseErrorMessage(e));
    } catch (e) {
      final errStr = e.toString();
      if (errStr.contains('Configuration not found') || errStr.contains('configuration-not-found')) {
        throw const AuthFailure(
          'Firebase Authentication is not enabled in Firebase Console yet. Please go to Firebase Console > Authentication > Get Started and enable Email/Password.',
        );
      }
      throw AuthFailure('Authentication error: $e');
    }
  }

  /// Registers user via Firebase Auth and creates Firestore user profile.
  Future<LoginResponse> register(String username, String email, String password) async {
    try {
      final user = await _firebaseService.register(
        username: username,
        email: email,
        password: password,
      );
      await _storageService.saveToken(user.id);
      await _storageService.saveUserEmail(user.email);
      return LoginResponse(token: user.id, type: 'Bearer', user: user);
    } on FirebaseAuthException catch (e) {
      throw AuthFailure(_mapFirebaseErrorMessage(e));
    } catch (e) {
      final errStr = e.toString();
      if (errStr.contains('Configuration not found') || errStr.contains('configuration-not-found')) {
        throw const AuthFailure(
          'Firebase Authentication is not enabled in Firebase Console yet. Please go to Firebase Console > Authentication > Get Started and enable Email/Password.',
        );
      }
      throw AuthFailure('Registration error: $e');
    }
  }

  /// Restores session from Firebase Auth / Firestore.
  Future<UserModel> getCurrentUser() async {
    try {
      final user = await _firebaseService.getCurrentUser();
      if (user != null) {
        return user;
      }
      throw const AuthFailure('No active user session');
    } catch (e) {
      throw AuthFailure(e.toString());
    }
  }

  /// Logs out user from Firebase Auth and clears secure storage.
  Future<void> logout() async {
    await _firebaseService.logout();
    await _storageService.clearAll();
  }

  /// Checks if an active session exists.
  Future<bool> hasStoredToken() async {
    if (_firebaseService.currentUser != null) return true;
    final token = await _storageService.getToken();
    return token != null && token.isNotEmpty;
  }

  String _mapFirebaseErrorMessage(FirebaseAuthException e) {
    final msg = (e.message ?? '').toLowerCase();
    final code = e.code.toLowerCase();

    if (code.contains('configuration-not-found') ||
        code.contains('configuration_not_found') ||
        msg.contains('configuration not found') ||
        msg.contains('configuration_not_found')) {
      return 'Firebase Authentication is not enabled in Firebase Console yet.\n\nTo fix:\n1. Open Firebase Console (console.firebase.google.com)\n2. Go to project: neurovault-app\n3. Click Authentication -> Get Started -> Email/Password -> Enable.';
    }

    switch (code) {
      case 'user-not-found':
        return 'No user found with this email address.';
      case 'wrong-password':
        return 'Incorrect password. Please try again.';
      case 'invalid-credential':
        return 'Invalid email or password.';
      case 'email-already-in-use':
        return 'An account already exists for this email address.';
      case 'invalid-email':
        return 'Please enter a valid email address.';
      case 'weak-password':
        return 'Password is too weak. Please use at least 6 characters.';
      case 'operation-not-allowed':
        return 'Email/Password authentication is disabled in Firebase Console. Enable it under Firebase Console > Authentication > Sign-in method.';
      case 'network-request-failed':
        return 'Network error: Check your internet connection.';
      default:
        return e.message ?? 'Authentication failed. Please try again.';
    }
  }
}
