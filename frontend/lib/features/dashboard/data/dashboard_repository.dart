import '../../../core/firebase/firebase_service.dart';
import '../../../repositories/base_repository.dart';
import '../../authentication/models/user_model.dart';
import '../models/dashboard_stats_model.dart';

/// Repository fetching and consolidating dashboard statistics via Firebase.
class DashboardRepository extends BaseRepository {
  final FirebaseService _firebaseService;

  DashboardRepository(this._firebaseService);

  Future<DashboardStatsModel> fetchDashboardStats() async {
    return safeApiCall(() async {
      final user = await _firebaseService.getCurrentUser() ??
          const UserModel(id: 'guest', username: 'Vault User', email: '', role: 'CLIENT');

      final files = await _firebaseService.listUserFiles();
      int storageUsed = 0;
      for (final f in files) {
        storageUsed += f.sizeBytes;
      }

      const int storageCapacity = 10 * 1024 * 1024 * 1024; // 10 GB default
      const int reservedCapacity = 5 * 1024 * 1024 * 1024; // 5 GB default

      return DashboardStatsModel(
        user: user,
        storageUsedBytes: storageUsed,
        storageCapacityBytes: storageCapacity,
        reservedStorageBytes: reservedCapacity,
        hostStatus: 'FIREBASE CLOUD 24/7',
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
