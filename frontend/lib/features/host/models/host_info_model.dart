/// Data model representing host node status and telemetry.
class HostInfoModel {
  final String id;
  final String name;
  final String deviceType;
  final String operatingSystem;
  final String publicIp;
  final String status; // 'ONLINE', 'OFFLINE', 'UNREGISTERED'
  final int totalCapacityBytes;
  final int reservedCapacityBytes;
  final int usedCapacityBytes;
  final int heartbeatIntervalSeconds;
  final DateTime? lastHeartbeat;
  final double cpuUsagePercent;
  final double ramUsagePercent;
  final bool containerCreated;
  final int activeChunks;

  const HostInfoModel({
    required this.id,
    required this.name,
    this.deviceType = 'Desktop',
    this.operatingSystem = 'Windows',
    this.publicIp = '127.0.0.1',
    this.status = 'UNREGISTERED',
    this.totalCapacityBytes = 50 * 1024 * 1024 * 1024,   // 50 GB default
    this.reservedCapacityBytes = 10 * 1024 * 1024 * 1024, // 10 GB default
    this.usedCapacityBytes = 0,
    this.heartbeatIntervalSeconds = 30,
    this.lastHeartbeat,
    this.cpuUsagePercent = 12.5,
    this.ramUsagePercent = 38.2,
    this.containerCreated = false,
    this.activeChunks = 0,
  });

  bool get isOnline => status == 'ONLINE';

  double get usagePercent {
    if (reservedCapacityBytes <= 0) return 0.0;
    return (usedCapacityBytes / reservedCapacityBytes).clamp(0.0, 1.0);
  }

  factory HostInfoModel.fromJson(Map<String, dynamic> json) {
    // Handle HostRegistrationResponse ({hostId, registrationStatus, heartbeatIntervalSeconds})
    final hostId = json['hostId']?.toString() ?? json['id']?.toString() ?? '';
    final rawStatus = json['status']?.toString() ?? json['registrationStatus']?.toString();
    final statusStr = (rawStatus == 'REGISTERED' || rawStatus == 'ONLINE') ? 'ONLINE' : (rawStatus ?? 'OFFLINE');

    return HostInfoModel(
      id: hostId,
      name: json['name']?.toString() ?? json['hostname']?.toString() ?? 'Micro Server Node',
      deviceType: json['deviceType']?.toString() ?? json['deviceName']?.toString() ?? 'Desktop',
      operatingSystem: json['operatingSystem']?.toString() ?? 'Windows',
      publicIp: json['publicIp']?.toString() ?? '127.0.0.1',
      status: statusStr,
      totalCapacityBytes: json['totalCapacityBytes'] ?? json['availableStorageBytes'] ?? 50 * 1024 * 1024 * 1024,
      reservedCapacityBytes: json['reservedCapacityBytes'] ?? json['reservedStorageBytes'] ?? 10 * 1024 * 1024 * 1024,
      usedCapacityBytes: json['usedCapacityBytes'] ?? json['usedStorageBytes'] ?? 0,
      heartbeatIntervalSeconds: json['heartbeatIntervalSeconds'] ?? 30,
      lastHeartbeat: json['lastHeartbeat'] != null ? DateTime.tryParse(json['lastHeartbeat'].toString()) : null,
      cpuUsagePercent: (json['cpuUsagePercent'] as num?)?.toDouble() ?? 12.5,
      ramUsagePercent: (json['ramUsagePercent'] as num?)?.toDouble() ?? 38.2,
      containerCreated: json['containerCreated'] ?? true,
      activeChunks: json['activeChunks'] ?? 0,
    );
  }

  HostInfoModel copyWith({
    String? id,
    String? name,
    String? deviceType,
    String? operatingSystem,
    String? publicIp,
    String? status,
    int? totalCapacityBytes,
    int? reservedCapacityBytes,
    int? usedCapacityBytes,
    int? heartbeatIntervalSeconds,
    DateTime? lastHeartbeat,
    double? cpuUsagePercent,
    double? ramUsagePercent,
    bool? containerCreated,
    int? activeChunks,
  }) {
    return HostInfoModel(
      id: id ?? this.id,
      name: name ?? this.name,
      deviceType: deviceType ?? this.deviceType,
      operatingSystem: operatingSystem ?? this.operatingSystem,
      publicIp: publicIp ?? this.publicIp,
      status: status ?? this.status,
      totalCapacityBytes: totalCapacityBytes ?? this.totalCapacityBytes,
      reservedCapacityBytes: reservedCapacityBytes ?? this.reservedCapacityBytes,
      usedCapacityBytes: usedCapacityBytes ?? this.usedCapacityBytes,
      heartbeatIntervalSeconds: heartbeatIntervalSeconds ?? this.heartbeatIntervalSeconds,
      lastHeartbeat: lastHeartbeat ?? this.lastHeartbeat,
      cpuUsagePercent: cpuUsagePercent ?? this.cpuUsagePercent,
      ramUsagePercent: ramUsagePercent ?? this.ramUsagePercent,
      containerCreated: containerCreated ?? this.containerCreated,
      activeChunks: activeChunks ?? this.activeChunks,
    );
  }
}
