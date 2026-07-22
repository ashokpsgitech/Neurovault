import '../../../core/constants/api_constants.dart';
import '../../../core/network/dio_client.dart';
import '../models/host_info_model.dart';

/// Network service performing REST HTTP calls to Spring Boot Coordinator /api/host/* and /api/storage/* endpoints.
class HostService {
  final DioClient _dioClient;

  HostService(this._dioClient);

  /// POST /api/host/register
  Future<HostInfoModel> registerHost({
    required String name,
    required String deviceType,
    required String operatingSystem,
    required String publicIp,
    required int totalCapacityBytes,
    required int reservedCapacityBytes,
  }) async {
    final response = await _dioClient.dio.post(
      ApiConstants.registerHost,
      data: {
        'hostname': name,
        'deviceName': deviceType,
        'operatingSystem': operatingSystem,
        'publicIp': publicIp,
        'availableStorageBytes': totalCapacityBytes,
        'reservedStorageBytes': reservedCapacityBytes,
      },
    );
    return HostInfoModel.fromJson(response.data);
  }

  /// POST /api/host/heartbeat
  Future<Map<String, dynamic>> sendHeartbeat({
    required String hostId,
    required double cpuUsagePercent,
    required double ramUsagePercent,
    required int usedCapacityBytes,
  }) async {
    final response = await _dioClient.dio.post(
      ApiConstants.heartbeat,
      data: {
        'hostId': hostId,
        'cpuUsagePercent': cpuUsagePercent,
        'ramUsagePercent': ramUsagePercent,
        'usedStorageBytes': usedCapacityBytes,
      },
    );
    return response.data;
  }

  /// GET /api/host/status
  Future<HostInfoModel?> getHostStatus() async {
    try {
      final response = await _dioClient.dio.get(ApiConstants.hostStatus);
      return HostInfoModel.fromJson(response.data);
    } catch (_) {
      return null;
    }
  }

  /// POST /api/storage/create
  Future<Map<String, dynamic>> createStorageContainer({
    required String containerPath,
    required String reservationSize,
  }) async {
    final response = await _dioClient.dio.post(
      ApiConstants.createStorage,
      data: {
        'containerPath': containerPath,
        'reservationSize': reservationSize,
      },
    );
    return response.data;
  }
}
