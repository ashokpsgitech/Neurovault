import 'package:go_router/go_router.dart';

import '../../features/authentication/screens/login_screen.dart';
import '../../features/authentication/screens/register_screen.dart';
import '../../features/dashboard/screens/dashboard_screen.dart';
import '../../features/files/screens/file_manager_screen.dart';
import '../../features/host/screens/host_screen.dart';
import '../../features/settings/screens/settings_screen.dart';
import '../../features/splash/splash_screen.dart';

/// App router configuration using go_router.
final GoRouter appRouter = GoRouter(
  initialLocation: '/splash',
  routes: [
    GoRoute(
      path: '/splash',
      builder: (context, state) => const SplashScreen(),
    ),
    GoRoute(
      path: '/login',
      builder: (context, state) => const LoginScreen(),
    ),
    GoRoute(
      path: '/register',
      builder: (context, state) => const RegisterScreen(),
    ),
    GoRoute(
      path: '/dashboard',
      builder: (context, state) => const DashboardScreen(),
    ),
    GoRoute(
      path: '/host',
      builder: (context, state) => const HostScreen(),
    ),
    GoRoute(
      path: '/files',
      builder: (context, state) => const FileManagerScreen(),
    ),
    GoRoute(
      path: '/settings',
      builder: (context, state) => const SettingsScreen(),
    ),
  ],
);
