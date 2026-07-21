/// Base failure class for error handling.
abstract class Failure {
  final String message;
  const Failure(this.message);
}

class NetworkFailure extends Failure {
  const NetworkFailure([super.message = 'Network connection error. Please check your connection.']);
}

class ServerFailure extends Failure {
  final int? statusCode;
  const ServerFailure(super.message, {this.statusCode});
}

class AuthFailure extends Failure {
  const AuthFailure([super.message = 'Authentication failed. Please log in again.']);
}

class StorageFailure extends Failure {
  const StorageFailure(super.message);
}
