import '../../../core/constants/api_constants.dart';
import '../../../core/network/dio_client.dart';
import '../../authentication/models/user_model.dart';

/// Network service performing REST HTTP calls to fetch dashboard statistics.
class DashboardService {
  final DioClient _dioClient;

  DashboardService(this._dioClient);

  /// GET /api/auth/me
  Future<UserModel> getProfile() async {
    final response = await _dioClient.dio.get(ApiConstants.currentUser);
    return UserModel.fromJson(response.data);
  }

  /// GET /api/host/status
  Future<Map<String, dynamic>?> getHostStatus() async {
    try {
      final response = await _dioClient.dio.get(ApiConstants.hostStatus);
      return response.data;
    } catch (_) {
      return null;
    }
  }

  /// GET /api/cluster/status
  Future<Map<String, dynamic>?> getClusterStatus() async {
    try {
      final response = await _dioClient.dio.get('/api/cluster/status');
      return response.data;
    } catch (_) {
      return null;
    }
  }
}
