import '../../../repositories/base_repository.dart';
import '../models/host_info_model.dart';
import '../services/host_service.dart';

/// Repository wrapping HostService calls with exception handling and container allocation.
class HostRepository extends BaseRepository {
  final HostService _service;

  HostRepository(this._service);

  Future<HostInfoModel> registerHost({
    required String name,
    required String deviceType,
    required String operatingSystem,
    required String publicIp,
    required int totalCapacityBytes,
    required int reservedCapacityBytes,
  }) async {
    return safeApiCall(() async {
      return await _service.registerHost(
        name: name,
        deviceType: deviceType,
        operatingSystem: operatingSystem,
        publicIp: publicIp,
        totalCapacityBytes: totalCapacityBytes,
        reservedCapacityBytes: reservedCapacityBytes,
      );
    });
  }

  Future<void> sendHeartbeat({
    required String hostId,
    required double cpuUsagePercent,
    required double ramUsagePercent,
    required int usedCapacityBytes,
  }) async {
    await safeApiCall(() async {
      await _service.sendHeartbeat(
        hostId: hostId,
        cpuUsagePercent: cpuUsagePercent,
        ramUsagePercent: ramUsagePercent,
        usedCapacityBytes: usedCapacityBytes,
      );
    });
  }

  Future<HostInfoModel?> getHostStatus() async {
    return safeApiCall(() async {
      return await _service.getHostStatus();
    });
  }

  Future<void> createStorageContainer(int reservedGb) async {
    await safeApiCall(() async {
      String reservationEnum = 'MEDIUM_5GB';
      if (reservedGb <= 1) reservationEnum = 'SMALL_1GB';
      else if (reservedGb >= 10) reservationEnum = 'LARGE_10GB';

      await _service.createStorageContainer(
        containerPath: './neurovault-storage/storage.container',
        reservationSize: reservationEnum,
      );
    });
  }
}
