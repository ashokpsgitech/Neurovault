import '../../authentication/models/user_model.dart';

/// Data model representing consolidated dashboard statistics.
class DashboardStatsModel {
  final UserModel user;
  final int storageUsedBytes;
  final int storageCapacityBytes;
  final int reservedStorageBytes;
  final String hostStatus;
  final int totalFiles;
  final List<RecentActivityItem> recentActivities;

  const DashboardStatsModel({
    required this.user,
    this.storageUsedBytes = 0,
    this.storageCapacityBytes = 10 * 1024 * 1024 * 1024, // 10 GB default
    this.reservedStorageBytes = 5 * 1024 * 1024 * 1024,  // 5 GB default
    this.hostStatus = 'UNREGISTERED',
    this.totalFiles = 0,
    this.recentActivities = const [],
  });

  double get storageUsagePercent {
    if (storageCapacityBytes <= 0) return 0.0;
    return (storageUsedBytes / storageCapacityBytes).clamp(0.0, 1.0);
  }

  DashboardStatsModel copyWith({
    UserModel? user,
    int? storageUsedBytes,
    int? storageCapacityBytes,
    int? reservedStorageBytes,
    String? hostStatus,
    int? totalFiles,
    List<RecentActivityItem>? recentActivities,
  }) {
    return DashboardStatsModel(
      user: user ?? this.user,
      storageUsedBytes: storageUsedBytes ?? this.storageUsedBytes,
      storageCapacityBytes: storageCapacityBytes ?? this.storageCapacityBytes,
      reservedStorageBytes: reservedStorageBytes ?? this.reservedStorageBytes,
      hostStatus: hostStatus ?? this.hostStatus,
      totalFiles: totalFiles ?? this.totalFiles,
      recentActivities: recentActivities ?? this.recentActivities,
    );
  }
}

class RecentActivityItem {
  final String id;
  final String title;
  final String subtitle;
  final String type; // 'UPLOAD' or 'DOWNLOAD'
  final DateTime timestamp;

  const RecentActivityItem({
    required this.id,
    required this.title,
    required this.subtitle,
    required this.type,
    required this.timestamp,
  });
}
