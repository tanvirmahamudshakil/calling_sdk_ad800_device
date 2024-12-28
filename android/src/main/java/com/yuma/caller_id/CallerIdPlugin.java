package com.yuma.caller_id;


import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;


/** CallerIdPlugin */
public class CallerIdPlugin implements FlutterPlugin, ActivityAware{
  private MethodChannel methodChannel;
  private EventChannel eventChannel;
  private EventChannelHelper connectionEventListener;
  private EventChannelHelper callingListener;
  private Activity context;
  private BinaryMessenger binaryMessenger;

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    binaryMessenger = binding.getBinaryMessenger();
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    methodChannel.setMethodCallHandler(null);
    this.context = null;
  }

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    context = (FlutterActivity) binding.getActivity();
    methodChannel = new MethodChannel(binaryMessenger, "caller_id");
    connectionEventListener = new EventChannelHelper(binaryMessenger, "caller_id/connection_listener");
    callingListener = new EventChannelHelper(binaryMessenger, "caller_id/calling_listener");
    MethodChannel.MethodCallHandler methodCallHandler = new MethodChannelHandler(context, connectionEventListener, callingListener);
    methodChannel.setMethodCallHandler(methodCallHandler);
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {

  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    Log.e("----------------------", "onReattachedToActivityForConfigChanges: Activity Reattached" );
  }

  @Override
  public void onDetachedFromActivity() {

  }
}