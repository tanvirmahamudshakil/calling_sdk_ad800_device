import 'dart:async';

import 'package:caller_id/call_state.dart';
import 'package:caller_id/calling_data.dart';
import 'package:flutter/services.dart';

class CallerIdSdk {
  static const MethodChannel _channel = MethodChannel("caller_id");
  static const EventChannel _connectionListener =
      EventChannel("caller_id/connection_listener");
  static const EventChannel _callingListener =
      EventChannel("caller_id/calling_listener");

  Future<void> connect() async {
    try {
      return await _channel.invokeMethod("connect");
    } catch (e) {
      throw FormatException("Error on connection. $e");
    }
  }

  Future<void> lineBusy() async {
    try {
      return await _channel.invokeMethod("lineBusy");
    } catch (e) {
      throw FormatException("Error on Line Busy. $e");
    }
  }

  Future<void> hangUp() async {
    try {
      return await _channel.invokeMethod("hangup");
    } catch (e) {
      throw FormatException("Error on Line hangup. $e");
    }
  }

  Future<void> disconnect() async {
    try {
      return await _channel.invokeMethod("disconnect");
    } catch (e) {
      throw FormatException("Error on disconnect: $e");
    }
  }

  Stream<bool> addConnectionListener() {
    return _connectionListener.receiveBroadcastStream().map((event) {
      bool state = false;
      if (event['connection_state'] == "connected") {
        state = true;
      } else {
        state = false;
      }
      return state;
    });
  }

  Stream<CallingData> addCallingStateListener() {
    return _callingListener.receiveBroadcastStream().map((event) {
      print("-----------stat: ${event['call_state']}");
      CallingData data = CallingData();
      CallState state = CallState.idle;
      switch (event['call_state']) {
        case "incoming":
          state = CallState.incomingCall;
          String phone = event['phone'] ?? "";
          print("------------(caller_id_sdk) phone: $phone");
          data = CallingData(callState: state, phoneNumber: phone);
          break;
        case "idle":
          state = CallState.idle;
          data = CallingData(callState: state, phoneNumber: null);
          break;
        case "answer":
          String phone = event['phone'] ?? "";
          state = CallState.answered;
          data = CallingData(callState: state, phoneNumber: phone);
          break;
        default:
          state = CallState.error;
          data = CallingData(callState: state, phoneNumber: null);
          break;
      }
      return data;
    });
  }
}
