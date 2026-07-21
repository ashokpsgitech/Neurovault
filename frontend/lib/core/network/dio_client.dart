import 'package:dio/dio.dart';
import '../config/env_config.dart';

/// Dio HTTP client configuration with timeouts and JWT interceptor.
class DioClient {
  final Dio dio;

  DioClient({required String baseUrl, Future<String?> Function()? getToken})
      : dio = Dio(
          BaseOptions(
            baseUrl: baseUrl,
            connectTimeout: const Duration(milliseconds: EnvConfig.connectTimeoutMs),
            receiveTimeout: const Duration(milliseconds: EnvConfig.receiveTimeoutMs),
            headers: {'Content-Type': 'application/json'},
          ),
        ) {
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) async {
          if (getToken != null) {
            final token = await getToken();
            if (token != null && token.isNotEmpty) {
              options.headers['Authorization'] = 'Bearer $token';
            }
          }
          return handler.next(options);
        },
        onError: (DioException error, handler) {
          return handler.next(error);
        },
      ),
    );
  }
}
