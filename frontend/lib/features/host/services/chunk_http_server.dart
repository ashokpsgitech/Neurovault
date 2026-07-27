import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import '../../../core/utils/debug_log_service.dart';
import '../data/host_repository.dart';

/// Lightweight built-in Dart HTTP server running on the host device.
/// Serves encrypted chunk payloads directly from local storage.container binary file
/// to authenticated network clients via GET /api/storage/chunks/{chunkId}.
/// Also accepts POST /api/storage/chunks/{chunkId} to write incoming chunk payloads.
class ChunkHttpServer {
  static final ChunkHttpServer _instance = ChunkHttpServer._internal();
  factory ChunkHttpServer() => _instance;
  ChunkHttpServer._internal();

  static const int kPort = 8080;

  HttpServer? _server;
  String? _containerPath;
  bool _running = false;

  bool get isRunning => _running;

  /// Starts the chunk HTTP server bound to all network interfaces on port 8080.
  Future<void> start(String containerPath) async {
    if (_running) {
      _containerPath = containerPath;
      return;
    }
    _containerPath = containerPath;
    try {
      _server = await HttpServer.bind(InternetAddress.anyIPv4, kPort, shared: true);
      _running = true;
      DebugLogService().info('[ChunkHttpServer] Host chunk server started on port $kPort — serving from: $containerPath');
      _handleRequests();
    } catch (e) {
      DebugLogService().warn('[ChunkHttpServer] Failed to start chunk server on port $kPort: $e');
    }
  }

  /// Stops the chunk HTTP server.
  Future<void> stop() async {
    _running = false;
    await _server?.close(force: true);
    _server = null;
    DebugLogService().info('[ChunkHttpServer] Host chunk server stopped.');
  }

  void updateContainerPath(String path) {
    _containerPath = path;
  }

  void _handleRequests() {
    _server?.listen((HttpRequest request) async {
      try {
        final uri = request.uri;
        final pathSegments = uri.pathSegments;

        // Expected: /api/storage/chunks/{chunkId}
        if (pathSegments.length >= 4 &&
            pathSegments[0] == 'api' &&
            pathSegments[1] == 'storage' &&
            pathSegments[2] == 'chunks') {
          final chunkId = pathSegments[3];

          if (request.method == 'GET') {
            await _handleGetChunk(request, chunkId);
          } else if (request.method == 'POST') {
            await _handlePostChunk(request, chunkId);
          } else {
            request.response.statusCode = HttpStatus.methodNotAllowed;
            await request.response.close();
          }
        } else if (uri.path == '/health' || uri.path == '/') {
          request.response
            ..statusCode = HttpStatus.ok
            ..headers.contentType = ContentType.text
            ..write('NeuroVault Host Node OK');
          await request.response.close();
        } else {
          request.response.statusCode = HttpStatus.notFound;
          await request.response.close();
        }
      } catch (e) {
        DebugLogService().warn('[ChunkHttpServer] Request handler error: $e');
        try {
          request.response.statusCode = HttpStatus.internalServerError;
          await request.response.close();
        } catch (_) {}
      }
    }, onError: (e) {
      DebugLogService().warn('[ChunkHttpServer] Server stream error: $e');
    });
  }

  Future<void> _handleGetChunk(HttpRequest request, String chunkId) async {
    final path = _containerPath;
    if (path == null || path.isEmpty) {
      request.response.statusCode = HttpStatus.serviceUnavailable;
      await request.response.close();
      return;
    }

    // Parse chunk index from chunkId: format = {fileId}_chunk_{index}
    int chunkIndex = 0;
    final match = RegExp(r'_chunk_(\d+)$').firstMatch(chunkId);
    if (match != null) {
      chunkIndex = int.tryParse(match.group(1) ?? '0') ?? 0;
    }

    try {
      final bytes = await HostRepository().readChunkFromLocalContainer(path, chunkIndex, 0, chunkId: chunkId);
      if (bytes != null && bytes.isNotEmpty) {
        request.response
          ..statusCode = HttpStatus.ok
          ..headers.contentType = ContentType.binary
          ..headers.set('Content-Length', bytes.length)
          ..headers.set('X-Chunk-Id', chunkId)
          ..headers.set('X-Chunk-Size', bytes.length);
        request.response.add(bytes);
        await request.response.flush();
        await request.response.close();
        DebugLogService().info('[ChunkHttpServer] Served chunk: $chunkId (${bytes.length} bytes)');
      } else {
        request.response.statusCode = HttpStatus.notFound;
        await request.response.close();
        DebugLogService().warn('[ChunkHttpServer] Chunk not found in container: $chunkId');
      }
    } catch (e) {
      DebugLogService().warn('[ChunkHttpServer] Error reading chunk $chunkId: $e');
      request.response.statusCode = HttpStatus.internalServerError;
      await request.response.close();
    }
  }

  Future<void> _handlePostChunk(HttpRequest request, String chunkId) async {
    final path = _containerPath;
    if (path == null || path.isEmpty) {
      request.response.statusCode = HttpStatus.serviceUnavailable;
      await request.response.close();
      return;
    }

    try {
      final bodyBytes = await request.fold<List<int>>([], (buf, chunk) => buf..addAll(chunk));
      if (bodyBytes.isEmpty) {
        request.response.statusCode = HttpStatus.badRequest;
        await request.response.close();
        return;
      }

      await HostRepository().writeChunkToLocalContainer(path, Uint8List.fromList(bodyBytes), chunkId: chunkId);
      request.response
        ..statusCode = HttpStatus.ok
        ..headers.contentType = ContentType.json
        ..write('{"status":"stored","chunkId":"$chunkId","bytes":${bodyBytes.length}}');
      await request.response.close();
      DebugLogService().info('[ChunkHttpServer] Received and stored chunk: $chunkId (${bodyBytes.length} bytes)');
    } catch (e) {
      DebugLogService().warn('[ChunkHttpServer] Error storing chunk $chunkId: $e');
      request.response.statusCode = HttpStatus.internalServerError;
      await request.response.close();
    }
  }
}
