import '../models/host_info_model.dart';

/// Immutable Host Mode state hierarchy.
abstract class HostState {
  const HostState();
}

class HostInitial extends HostState {
  const HostInitial();
}

class HostLoading extends HostState {
  const HostLoading();
}

class HostDisabled extends HostState {
  final HostInfoModel? info;
  const HostDisabled([this.info]);
}

class HostEnabled extends HostState {
  final HostInfoModel info;
  const HostEnabled(this.info);
}

class HostError extends HostState {
  final String message;
  const HostError(this.message);
}
