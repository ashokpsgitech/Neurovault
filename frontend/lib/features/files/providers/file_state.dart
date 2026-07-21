import '../models/file_metadata_model.dart';
import '../models/progress_model.dart';

/// Immutable File Engine State hierarchy.
abstract class FileState {
  const FileState();
}

class FileInitial extends FileState {
  const FileInitial();
}

class FileLoading extends FileState {
  const FileLoading();
}

class FileLoaded extends FileState {
  final List<FileItem> files;
  final PipelineProgress? activeProgress;

  const FileLoaded(this.files, [this.activeProgress]);

  FileLoaded copyWith({
    List<FileItem>? files,
    PipelineProgress? activeProgress,
  }) {
    return FileLoaded(
      files ?? this.files,
      activeProgress,
    );
  }
}

class FileError extends FileState {
  final String message;
  const FileError(this.message);
}
