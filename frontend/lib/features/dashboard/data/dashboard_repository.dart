import '../../../core/firebase/firebase_service.dart';
import '../../../core/storage/secure_storage_service.dart';
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

      int hostStorageUsed = storageUsed;
      int reservedCapacity = 10 * 1024 * 1024 * 1024; // 10 GB initial fallback
      try {
        final hostSnap = await _firebaseService.getHostDocByOwner(user.id);
        if (hostSnap != null && hostSnap.data() != null) {
          final data = hostSnap.data()!;
          hostStorageUsed = data['usedStorageBytes'] ?? data['usedCapacityBytes'] ?? storageUsed;
          if (data['reservedStorageBytes'] != null && (data['reservedStorageBytes'] as int) > 0) {
            reservedCapacity = data['reservedStorageBytes'];
          }
        }
      } catch (_) {}

      // Fall back to saved container size in SecureStorageService if Firestore snap not set yet
      try {
        final savedGb = await SecureStorageService().getHostContainerSizeGb();
        if (savedGb != null && savedGb > 0) {
          reservedCapacity = savedGb * 1024 * 1024 * 1024;
        }
      } catch (_) {}

      final int storageCapacity = reservedCapacity;

      return DashboardStatsModel(
        user: user,
        storageUsedBytes: storageUsed,
        storageCapacityBytes: storageCapacity,
        reservedStorageBytes: reservedCapacity,
        hostStatus: 'FIREBASE CLOUD 24/7',
        activeHostsCount: activeHosts,
        activeUsersCount: 1,
        hostStorageUsedBytes: hostStorageUsed,
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
