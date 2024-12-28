import 'package:caller_id/call_state.dart';

class CallingData {
  CallState callState;
  String? phoneNumber;

  CallingData({ this.callState = CallState.idle, this.phoneNumber});
}
