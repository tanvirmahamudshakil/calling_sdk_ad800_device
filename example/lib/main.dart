import 'dart:async';

import 'package:caller_id/caller_id_sdk.dart';
import 'package:caller_id/calling_data.dart';
import 'package:flutter/material.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final _callerIdSdk = CallerIdSdk();

  @override
  void initState() {
    connect();
    super.initState();
  }

  @override
  void dispose() {
    //_callerIdSdk
    super.dispose();
  }

  Future<void> connect() async {
    await _callerIdSdk.connect();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Plugin example app'),
        ),
        body: Center(
          child: Column(
            children: [
              StreamBuilder<bool>(
                stream: _callerIdSdk.addConnectionListener(),
                builder: (context, snapshot) {
                  bool status = snapshot.data ?? false;
                  return Text("Connection status: $status");
                },
              ),
              const SizedBox(height: 20),
              StreamBuilder<CallingData>(
                  stream: _callerIdSdk.addCallingStateListener(),
                  builder: (context, snapshot) {
                    CallingData? data = snapshot.data;
                    return Column(
                      children: [
                        Text("Calling state: ${data?.callState.name}"),
                        Text("Phone Number: ${data?.phoneNumber}"),
                      ],
                    );
                  })
            ],
          ),
        ),
      ),
    );
  }
}
