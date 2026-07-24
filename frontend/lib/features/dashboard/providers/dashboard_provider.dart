import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/errors/failures.dart';
import '../../../core/firebase/firebase_service.dart';
import '../data/dashboard_repository.dart';
import 'dashboard_state.dart';

final dashboardRepositoryProvider = Provider<DashboardRepository>((ref) {
  final firebaseService = FirebaseService();
  return DashboardRepository(firebaseService);
});

final dashboardProvider = StateNotifierProvider<DashboardNotifier, DashboardState>((ref) {
  final repo = ref.watch(dashboardRepositoryProvider);
  return DashboardNotifier(repo);
});

/// Riverpod StateNotifier managing Dashboard metrics and real-time refresh.
class DashboardNotifier extends StateNotifier<DashboardState> {
  final DashboardRepository _repository;

  DashboardNotifier(this._repository) : super(const DashboardInitial()) {
    loadDashboard();
  }

  Future<void> loadDashboard() async {
    state = const DashboardLoading();
    try {
      final stats = await _repository.fetchDashboardStats();
      state = DashboardLoaded(stats);
    } on Failure catch (f) {
      state = DashboardError(f.message);
    } catch (e) {
      state = DashboardError(e.toString());
    }
  }

  Future<void> refresh() async {
    await loadDashboard();
  }
}
