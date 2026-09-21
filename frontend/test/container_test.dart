import 'dart:io';
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:neurovault_frontend/features/host/data/host_repository.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Direct Binary Container File I/O Tests', () {
    late Directory tempDir;
    late String containerPath;
    late HostRepository hostRepo;

    setUp(() async {
      tempDir = await Directory.systemTemp.createTemp('nv_container_test_');
      containerPath = '${tempDir.path}/test_storage.container';
      hostRepo = HostRepository();
    });

    tearDown(() async {
      if (await tempDir.exists()) {
        await tempDir.delete(recursive: true);
      }
    });

    test('Store and Read Multiple Chunks Directly Inside Single Container File', () async {
      final chunk0Data = Uint8List.fromList([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
      final chunk1Data = Uint8List.fromList(List.generate(500, (i) => (i * 3) % 256));
      final chunk2Data = Uint8List.fromList(List.generate(1024, (i) => (i * 7) % 256));

      // 1. Write chunk 0 into container
      await hostRepo.writeChunkToLocalContainer(containerPath, chunk0Data, chunkId: 'file_99_chunk_0');

      // Verify no loose folder or external .bin file was created
      final parentFiles = tempDir.listSync();
      expect(parentFiles.length, equals(1)); // ONLY the test_storage.container file exists!
      expect(parentFiles.first.path.endsWith('test_storage.container'), isTrue);

      // 2. Write chunk 1 and chunk 2
      await hostRepo.writeChunkToLocalContainer(containerPath, chunk1Data, chunkId: 'file_99_chunk_1');
      await hostRepo.writeChunkToLocalContainer(containerPath, chunk2Data, chunkId: 'file_99_chunk_2');

      // Still only 1 single file on disk
      expect(tempDir.listSync().length, equals(1));

      // 3. Read chunks back directly from inside container by chunkId
      final read0 = await hostRepo.readChunkFromLocalContainer(containerPath, 0, 0, chunkId: 'file_99_chunk_0');
      final read1 = await hostRepo.readChunkFromLocalContainer(containerPath, 1, 0, chunkId: 'file_99_chunk_1');
      final read2 = await hostRepo.readChunkFromLocalContainer(containerPath, 2, 0, chunkId: 'file_99_chunk_2');

      expect(read0, isNotNull);
      expect(read0, equals(chunk0Data));

      expect(read1, isNotNull);
      expect(read1, equals(chunk1Data));

      expect(read2, isNotNull);
      expect(read2, equals(chunk2Data));

      // 4. Verify binary container header
      final file = File(containerPath);
      final raf = await file.open(mode: FileMode.read);
      final headerBytes = await raf.read(256);
      await raf.close();

      final bd = ByteData.sublistView(headerBytes);
      // Magic NVLT
      expect(headerBytes[0], equals(0x4E));
      expect(headerBytes[1], equals(0x56));
      expect(headerBytes[2], equals(0x4C));
      expect(headerBytes[3], equals(0x54));

      final usedSize = bd.getInt64(16, Endian.big);
      final chunkCount = bd.getInt32(24, Endian.big);

      expect(usedSize, equals(chunk0Data.length + chunk1Data.length + chunk2Data.length));
      expect(chunkCount, equals(3));
    });
  });
}
