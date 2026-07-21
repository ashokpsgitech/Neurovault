import '../models/dashboard_stats_model.dart';

/// Immutable Dashboard State hierarchy.
abstract class DashboardState {
  const DashboardState();
}

class DashboardInitial extends DashboardState {
  const DashboardInitial();
}

class DashboardLoading extends DashboardState {
  const DashboardLoading();
}

class DashboardLoaded extends DashboardState {
  final DashboardStatsModel stats;
  const DashboardLoaded(this.stats);
}

class DashboardError extends DashboardState {
  final String message;
  const DashboardError(this.message);
}
