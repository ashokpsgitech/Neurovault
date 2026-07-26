import 'package:flutter/foundation.dart';

class LogEntry {
  final DateTime timestamp;
  final String message;
  final String level; // 'INFO', 'WARN', 'ERROR'
  final String? error;
  final String? stackTrace;

  LogEntry({
    required this.timestamp,
    required this.message,
    this.level = 'INFO',
    this.error,
    this.stackTrace,
  });

  String get timeFormatted =>
      '${timestamp.hour.toString().padLeft(2, '0')}:${timestamp.minute.toString().padLeft(2, '0')}:${timestamp.second.toString().padLeft(2, '0')}';
}

/// Global In-App Debug Logger Service
class DebugLogService extends ChangeNotifier {
  static final DebugLogService _instance = DebugLogService._internal();
  factory DebugLogService() => _instance;
  DebugLogService._internal();

  final List<LogEntry> _logs = [];
  List<LogEntry> get logs => List.unmodifiable(_logs.reversed);

  void log(String message, {String level = 'INFO', Object? error, StackTrace? stackTrace}) {
    final entry = LogEntry(
      timestamp: DateTime.now(),
      message: message,
      level: level,
      error: error?.toString(),
      stackTrace: stackTrace?.toString(),
    );

    _logs.add(entry);
    if (_logs.length > 300) {
      _logs.removeAt(0); // keep last 300 logs
    }

    if (kDebugMode) {
      debugPrint('[${entry.level}] ${entry.message}');
      if (error != null) debugPrint(' -> Error: $error');
    }

    notifyListeners();
  }

  void error(String message, [Object? err, StackTrace? st]) {
    log(message, level: 'ERROR', error: err, stackTrace: st);
  }

  void warn(String message) {
    log(message, level: 'WARN');
  }

  void info(String message) {
    log(message, level: 'INFO');
  }

  void clear() {
    _logs.clear();
    notifyListeners();
  }

  String exportLogsText() {
    return _logs.map((l) {
      var s = '[${l.timeFormatted}][${l.level}] ${l.message}';
      if (l.error != null) s += '\nError: ${l.error}';
      if (l.stackTrace != null) s += '\nStackTrace: ${l.stackTrace}';
      return s;
    }).join('\n---\n');
  }
}
