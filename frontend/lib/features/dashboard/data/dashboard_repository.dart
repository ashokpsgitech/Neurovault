import '../../../repositories/base_repository.dart';
import '../models/dashboard_stats_model.dart';
import '../services/dashboard_service.dart';

/// Repository fetching and consolidating dashboard statistics.
class DashboardRepository extends BaseRepository {
  final DashboardService _service;

  DashboardRepository(this._service);

  Future<DashboardStatsModel> fetchDashboardStats() async {
    return safeApiCall(() async {
      final user = await _service.getProfile();
      final hostData = await _service.getHostStatus();
      final clusterData = await _service.getClusterStatus();

      String hostStatus = 'UNREGISTERED';
      int storageUsed = 0;
      int storageCapacity = 10 * 1024 * 1024 * 1024; // 10 GB default
      int reservedCapacity = 5 * 1024 * 1024 * 1024; // 5 GB default

      if (hostData != null) {
        hostStatus = hostData['status']?.toString() ?? 'OFFLINE';
        storageUsed = hostData['usedCapacityBytes'] ?? 0;
        storageCapacity = hostData['totalCapacityBytes'] ?? storageCapacity;
        reservedCapacity = hostData['reservedCapacityBytes'] ?? reservedCapacity;
      }

      int totalFiles = clusterData?['totalFiles'] ?? 0;

      return DashboardStatsModel(
        user: user,
        storageUsedBytes: storageUsed,
        storageCapacityBytes: storageCapacity,
        reservedStorageBytes: reservedCapacity,
        hostStatus: hostStatus,
        totalFiles: totalFiles,
        recentActivities: [
          RecentActivityItem(
            id: '1',
            title: 'System Vault Initialized',
            subtitle: 'Secure AES-256 key envelope created',
            type: 'UPLOAD',
            timestamp: DateTime.now().subtract(const Duration(minutes: 5)),
          ),
        ],
      );
    });
  }
}
