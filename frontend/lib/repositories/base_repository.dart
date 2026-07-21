import 'package:dio/dio.dart';
import '../core/errors/failures.dart';

/// Base repository providing safe HTTP execution and exception mapping.
abstract class BaseRepository {
  Future<T> safeApiCall<T>(Future<T> Function() apiCall) async {
    try {
      return await apiCall();
    } on DioException catch (e) {
      if (e.type == DioExceptionType.connectionTimeout ||
          e.type == DioExceptionType.receiveTimeout ||
          e.type == DioExceptionType.connectionError) {
        throw const NetworkFailure();
      }
      if (e.response != null) {
        final statusCode = e.response!.statusCode;
        final message = e.response!.data?['message']?.toString() ?? 'Server error occurred';
        if (statusCode == 401 || statusCode == 403) {
          throw AuthFailure(message);
        }
        throw ServerFailure(message, statusCode: statusCode);
      }
      throw ServerFailure(e.message ?? 'Unexpected network error');
    } catch (e) {
      throw ServerFailure(e.toString());
    }
  }
}
