import '../../../core/firebase/firebase_service.dart';
import '../../../repositories/base_repository.dart';
import '../../authentication/models/user_model.dart';
import '../../files/models/file_metadata_model.dart';
import '../models/dashboard_stats_model.dart';

/// Repository fetching and consolidating dashboard statistics strictly online via Firebase Cloud Services.
class DashboardRepository extends BaseRepository {
  final FirebaseService _firebaseService;

  DashboardRepository(this._firebaseService);

  Future<DashboardStatsModel> fetchDashboardStats() async {
    return safeApiCall(() async {
      final user = await _firebaseService.getCurrentUser() ??
          const UserModel(id: 'guest', username: 'Vault User', email: '', role: 'CLIENT');

      List<FileItem> files = [];
      try {
        files = await _firebaseService.listUserFiles();
      } catch (_) {}

      int storageUsed = 0;
      for (final f in files) {
        storageUsed += f.sizeBytes;
      }

      int activeHosts = 1;
      try {
        activeHosts = await _firebaseService.getActiveHostsCount();
      } catch (_) {}

      const int storageCapacity = 10 * 1024 * 1024 * 1024; // 10 GB default
      const int reservedCapacity = 5 * 1024 * 1024 * 1024; // 5 GB default

      return DashboardStatsModel(
        user: user,
        storageUsedBytes: storageUsed,
        storageCapacityBytes: storageCapacity,
        reservedStorageBytes: reservedCapacity,
        hostStatus: 'FIREBASE CLOUD 24/7',
        activeHostsCount: activeHosts,
        activeUsersCount: 1,
        hostStorageUsedBytes: storageUsed,
        totalFiles: files.length,
        recentActivities: files.map((f) => RecentActivityItem(
          id: f.id,
          title: f.filename,
          subtitle: 'AES-256 Cloud Encrypted Sync',
          type: 'UPLOAD',
          timestamp: f.createdAt,
        )).toList(),
      );
    });
  }
}
