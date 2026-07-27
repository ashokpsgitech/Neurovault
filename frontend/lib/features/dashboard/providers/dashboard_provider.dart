import 'dart:async';
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
  StreamSubscription<int>? _activeHostsSubscription;

  DashboardNotifier(this._repository) : super(const DashboardInitial()) {
    loadDashboard();
    _listenToActiveHostsStream();
  }

  void _listenToActiveHostsStream() {
    _activeHostsSubscription?.cancel();
    _activeHostsSubscription = FirebaseService().streamActiveHostsCount().listen((count) {
      if (state is DashboardLoaded) {
        final currentStats = (state as DashboardLoaded).stats;
        if (currentStats.activeHostsCount != count) {
          state = DashboardLoaded(currentStats.copyWith(activeHostsCount: count));
        }
      }
    });
  }

  Future<void> loadDashboard() async {
    state = const DashboardLoading();
    try {
      final stats = await _repository.fetchDashboardStats();
      state = DashboardLoaded(stats);
      _listenToActiveHostsStream();
    } on Failure catch (f) {
      state = DashboardError(f.message);
    } catch (e) {
      state = DashboardError(e.toString());
    }
  }

  Future<void> refresh() async {
    await loadDashboard();
  }

  @override
  void dispose() {
    _activeHostsSubscription?.cancel();
    super.dispose();
  }
}
