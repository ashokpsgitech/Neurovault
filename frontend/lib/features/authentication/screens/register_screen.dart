import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../widgets/custom_snackbar.dart';
import '../../../widgets/loading_overlay.dart';
import '../providers/auth_provider.dart';
import '../providers/auth_state.dart';

/// Responsive Material 3 Firebase Authentication Register Screen with Email OTP/Verification.
class RegisterScreen extends ConsumerStatefulWidget {
  const RegisterScreen({super.key});

  @override
  ConsumerState<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends ConsumerState<RegisterScreen> {
  final _formKey = GlobalKey<FormState>();
  final _usernameController = TextEditingController();
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();

  bool _obscurePassword = true;
  bool _obscureConfirmPassword = true;
  bool _showVerificationStep = false;
  bool _isCheckingVerification = false;
  Timer? _verificationTimer;

  @override
  void dispose() {
    _verificationTimer?.cancel();
    _usernameController.dispose();
    _emailController.dispose();
    _passwordController.dispose();
    _confirmPasswordController.dispose();
    super.dispose();
  }

  void _startVerificationCheckTimer() {
    _verificationTimer?.cancel();
    _verificationTimer = Timer.periodic(const Duration(seconds: 3), (_) async {
      final isVerified = await ref.read(authStateProvider.notifier).checkEmailVerified();
      if (isVerified && mounted) {
        _verificationTimer?.cancel();
        CustomSnackbar.showSuccess(context, 'Email verified successfully! Welcome to NeuroVault.');
        context.go('/dashboard');
      }
    });
  }

  Future<void> _manualCheckVerification() async {
    setState(() => _isCheckingVerification = true);
    final isVerified = await ref.read(authStateProvider.notifier).checkEmailVerified();
    setState(() => _isCheckingVerification = false);

    if (mounted) {
      if (isVerified) {
        _verificationTimer?.cancel();
        CustomSnackbar.showSuccess(context, 'Email verified! Proceeding to Dashboard...');
        context.go('/dashboard');
      } else {
        CustomSnackbar.showError(context, 'Email not verified yet. Please check your email inbox.');
      }
    }
  }

  Future<void> _resendVerification() async {
    try {
      await ref.read(authStateProvider.notifier).resendEmailVerification();
      if (mounted) {
        CustomSnackbar.showSuccess(context, 'Verification email resent to ${_emailController.text.trim()}');
      }
    } catch (e) {
      if (mounted) {
        CustomSnackbar.showError(context, 'Failed to resend verification email: $e');
      }
    }
  }

  void _submitRegister() {
    if (_formKey.currentState?.validate() ?? false) {
      ref.read(authStateProvider.notifier).register(
            _usernameController.text.trim(),
            _emailController.text.trim(),
            _passwordController.text,
          );
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final authState = ref.watch(authStateProvider);
    final isLoading = authState is AuthLoading;

    ref.listen<AuthState>(authStateProvider, (previous, next) {
      if (next is Authenticated) {
        if (!_showVerificationStep) {
          setState(() {
            _showVerificationStep = true;
          });
          _startVerificationCheckTimer();
          CustomSnackbar.showSuccess(
            context,
            'Verification email sent to ${_emailController.text.trim()}',
          );
        }
      } else if (next is AuthError) {
        CustomSnackbar.showError(context, next.message);
      }
    });

    return Scaffold(
      body: LoadingOverlay(
        isLoading: isLoading || _isCheckingVerification,
        message: _isCheckingVerification ? 'Checking verification...' : 'Creating account...',
        child: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24.0),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 460),
                child: Card(
                  elevation: 2,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(24),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(32.0),
                    child: _showVerificationStep
                        ? _buildEmailVerificationStep(theme)
                        : _buildRegistrationFormStep(theme, isLoading),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// Step 1: User Details Form & Google Sign In
  Widget _buildRegistrationFormStep(ThemeData theme, bool isLoading) {
    return Form(
      key: _formKey,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Icon(
            Icons.person_add_outlined,
            size: 56,
            color: theme.colorScheme.primary,
          ),
          const SizedBox(height: 16),
          Text(
            'Create Account',
            textAlign: TextAlign.center,
            style: theme.textTheme.headlineMedium?.copyWith(
              fontWeight: FontWeight.bold,
              color: theme.colorScheme.onSurface,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            'Join NeuroVault 24/7 Firebase storage',
            textAlign: TextAlign.center,
            style: theme.textTheme.bodyMedium?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 32),

          // Google 1-Tap Sign In
          OutlinedButton.icon(
            icon: const Icon(Icons.g_mobiledata, size: 28, color: Colors.red),
            label: const Text('Sign in with Google'),
            style: OutlinedButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 14),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            onPressed: isLoading
                ? null
                : () {
                    ref.read(authStateProvider.notifier).signInWithGoogle();
                  },
          ),
          const SizedBox(height: 20),
          const Row(
            children: [
              Expanded(child: Divider()),
              Padding(
                padding: EdgeInsets.symmetric(horizontal: 12),
                child: Text('OR REGISTER WITH EMAIL', style: TextStyle(color: Colors.grey, fontSize: 11, fontWeight: FontWeight.bold)),
              ),
              Expanded(child: Divider()),
            ],
          ),
          const SizedBox(height: 20),

          // Username Field
          TextFormField(
            controller: _usernameController,
            textInputAction: TextInputAction.next,
            decoration: const InputDecoration(
              labelText: 'Full Name / Username',
              prefixIcon: Icon(Icons.person_outlined),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.all(Radius.circular(12)),
              ),
            ),
            validator: (value) {
              if (value == null || value.trim().isEmpty) {
                return 'Username is required';
              }
              if (value.trim().length < 3) {
                return 'Username must be at least 3 characters';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),

          // Email Field
          TextFormField(
            controller: _emailController,
            keyboardType: TextInputType.emailAddress,
            textInputAction: TextInputAction.next,
            decoration: const InputDecoration(
              labelText: 'Email Address',
              prefixIcon: Icon(Icons.email_outlined),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.all(Radius.circular(12)),
              ),
            ),
            validator: (value) {
              if (value == null || value.trim().isEmpty) {
                return 'Email is required';
              }
              if (!RegExp(r'^[\w-\.]+@([\w-]+\.)+[\w-]{2,4}$').hasMatch(value.trim())) {
                return 'Please enter a valid email address';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),

          // Password Field
          TextFormField(
            controller: _passwordController,
            obscureText: _obscurePassword,
            textInputAction: TextInputAction.next,
            decoration: InputDecoration(
              labelText: 'Password',
              prefixIcon: const Icon(Icons.lock_outlined),
              suffixIcon: IconButton(
                icon: Icon(
                  _obscurePassword ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                ),
                onPressed: () {
                  setState(() {
                    _obscurePassword = !_obscurePassword;
                  });
                },
              ),
              border: const OutlineInputBorder(
                borderRadius: BorderRadius.all(Radius.circular(12)),
              ),
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return 'Password is required';
              }
              if (value.length < 6) {
                return 'Password must be at least 6 characters';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),

          // Confirm Password Field
          TextFormField(
            controller: _confirmPasswordController,
            obscureText: _obscureConfirmPassword,
            textInputAction: TextInputAction.done,
            onFieldSubmitted: (_) => _submitRegister(),
            decoration: InputDecoration(
              labelText: 'Confirm Password',
              prefixIcon: const Icon(Icons.lock_clock_outlined),
              suffixIcon: IconButton(
                icon: Icon(
                  _obscureConfirmPassword ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                ),
                onPressed: () {
                  setState(() {
                    _obscureConfirmPassword = !_obscureConfirmPassword;
                  });
                },
              ),
              border: const OutlineInputBorder(
                borderRadius: BorderRadius.all(Radius.circular(12)),
              ),
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return 'Please confirm your password';
              }
              if (value != _passwordController.text) {
                return 'Passwords do not match';
              }
              return null;
            },
          ),
          const SizedBox(height: 28),

          // Submit Button
          FilledButton.icon(
            icon: const Icon(Icons.mark_email_read_outlined),
            label: const Text(
              'Register & Send Verification Code',
              style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
            ),
            onPressed: isLoading ? null : _submitRegister,
            style: FilledButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 16),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
          ),
          const SizedBox(height: 24),

          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                'Already have an account?',
                style: theme.textTheme.bodyMedium,
              ),
              TextButton(
                onPressed: () => context.go('/login'),
                child: const Text('Sign In'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// Step 2: Email OTP / Verification Card Step
  Widget _buildEmailVerificationStep(ThemeData theme) {
    final emailText = _emailController.text.trim();
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        CircleAvatar(
          radius: 36,
          backgroundColor: theme.colorScheme.primaryContainer,
          child: Icon(
            Icons.mark_email_unread_outlined,
            size: 40,
            color: theme.colorScheme.primary,
          ),
        ),
        const SizedBox(height: 20),
        Text(
          'Verify Your Email',
          textAlign: TextAlign.center,
          style: theme.textTheme.headlineMedium?.copyWith(
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 12),
        Text(
          'A verification email and link has been sent to:',
          textAlign: TextAlign.center,
          style: theme.textTheme.bodyMedium?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 6),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(
            color: theme.colorScheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Text(
            emailText,
            textAlign: TextAlign.center,
            style: theme.textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.bold,
              color: theme.colorScheme.primary,
            ),
          ),
        ),
        const SizedBox(height: 24),

        // Interactive Status Card
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            border: Border.all(color: theme.colorScheme.outlineVariant),
            borderRadius: BorderRadius.circular(16),
          ),
          child: const Row(
            children: [
              CircularProgressIndicator(strokeWidth: 2.5),
              SizedBox(width: 16),
              Expanded(
                child: Text(
                  'Waiting for email verification... Tap link in your inbox or press check status.',
                  style: TextStyle(fontSize: 13),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 24),

        // Action Buttons
        FilledButton.icon(
          icon: const Icon(Icons.verified_user_outlined),
          label: const Text('I Have Verified My Email', style: TextStyle(fontWeight: FontWeight.bold)),
          style: FilledButton.styleFrom(
            padding: const EdgeInsets.symmetric(vertical: 16),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          ),
          onPressed: _manualCheckVerification,
        ),
        const SizedBox(height: 12),

        OutlinedButton.icon(
          icon: const Icon(Icons.send_outlined),
          label: const Text('Resend Verification Email'),
          style: OutlinedButton.styleFrom(
            padding: const EdgeInsets.symmetric(vertical: 14),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          ),
          onPressed: _resendVerification,
        ),
        const SizedBox(height: 16),

        TextButton(
          onPressed: () {
            _verificationTimer?.cancel();
            setState(() => _showVerificationStep = false);
          },
          child: const Text('Edit Email Address'),
        ),
      ],
    );
  }
}
