package com.yuma.caller_id.usbController;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import com.yuma.caller_id.EventChannelHelper;
import com.yuma.caller_id.model.Channel;
import com.yuma.caller_id.utils.Constants;
import com.yuma.caller_id.utils.DtmfData;
import com.yuma.caller_id.utils.FileLog;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class UsbHelper {
    private static final String TAG = UsbHelper.class.getSimpleName();
    public static Context context;
    public static String DeviceSN = "", DeviceVer = "";            //Device Serial Number and Device Version
    public static List<Channel> channelList = new ArrayList<Channel>();        //Channel List
    public static int selectedChannel = -1;
    private final EventChannelHelper connectionListener;
    private static EventChannelHelper callingListener;
    private UsbManager mManager;
    public List<DeviceID> devices = new ArrayList<DeviceID>();
    public static UsbController sUsbController = null;
    public static int callingStatus = 0;


    public UsbHelper(Context context, EventChannelHelper connectionListener, EventChannelHelper callingListener) {
        UsbHelper.context = context;
        this.connectionListener = connectionListener;
        UsbHelper.callingListener = callingListener;
        DtmfData.DtmfDataInit1();
        DtmfData.DtmfDataInit2();
        DtmfData.DtmfDataInit3();
        DtmfData.DtmfDataInit4();

        File folder = new File(Environment.getExternalStorageDirectory() + "/AD800");
        if (!folder.exists()) folder.mkdir();
        //Open Log.txt
        File Root = Environment.getExternalStorageDirectory();
        if (Root.canWrite()) {
            File LogFile = new File(Root, "Log.txt");
            if (LogFile.exists()) LogFile.delete();
            FileLog.open(LogFile.getAbsolutePath(), Log.VERBOSE, 1000000);
            FileLog.v("FileLog", "start");
        }
        selectedChannel = -1;
        channelList.clear();
        for (int i = 0; i < 8; i++) {
            Channel channel = new Channel();
            channel.m_Channel = i;
            channelList.add(channel);
        }
        mManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void initUSB() {
        mManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        for (UsbDevice device : mManager.getDeviceList().values()) {
            if (device.getVendorId() == Constants.DEVICE_VENDOR_ID && device.getProductId() == Constants.DEVICE_PRODUCT_ID) {
                setDeviceConnect(true);
            }
        }
        //Register Device attached or detached receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        context.registerReceiver(mUsbReceiver, filter);
        //for testing
        devices.add(new DeviceID(Constants.DEVICE_VENDOR_ID, Constants.DEVICE_PRODUCT_ID));
        if (sUsbController == null) {
            sUsbController = new UsbController(context, mConnectionHandler, devices);
        }
    }

    public void lineBusy() {
        if (sUsbController != null) {
            sUsbController.SendPickUpCommand(selectedChannel);
        }
    }

    public void hangup() {
        if (sUsbController != null) {
            sUsbController.SendHangUpCommand(selectedChannel);
        }
    }

    public void disconnectAll() {
        FileLog.v(TAG, "Activity is destroyed");
        FileLog.close();
        context.unregisterReceiver(mUsbReceiver);
        try {
            if (sUsbController != null) {
                sUsbController.stop();
                sUsbController = null;
            }
        } catch (Exception e) {
            FileLog.v("Destory", "UsbController " + e.getLocalizedMessage());
        }
    }


    private void setDeviceConnect(boolean isConn) {
        if (isConn) {
            devices.clear();
            devices.add(new DeviceID(Constants.DEVICE_VENDOR_ID, Constants.DEVICE_PRODUCT_ID));
            if (sUsbController == null) {
                sUsbController = new UsbController(context, mConnectionHandler, devices);
            }
            Toast.makeText(context, "Device Connected", Toast.LENGTH_LONG).show();
            Map<String, String> map = new HashMap<>();
            map.put("connection_state", "connected");
            connectionListener.success(map);
        } else {
            if (sUsbController != null) {
                sUsbController.stop();
                sUsbController = null;
            }
            deviceDataInit();
            Toast.makeText(context, "Connection lost", Toast.LENGTH_LONG).show();
            //connectionListener.error("400", "Disconnected", "Failed");
            Map<String, String> map = new HashMap<>();
            map.put("connection_state", "disconnected");
            connectionListener.success(map);
        }
    }

    public void deviceDataInit() {
        selectedChannel = 0;
        for (int i = 0; i < 8; i++) {
            channelList.get(i).init();
        }
    }

    private final IUsbConnectionHandler mConnectionHandler = new IUsbConnectionHandler() {
        @Override
        public void onUsbStopped() {
            Log.e(TAG, "Usb stopped!");
        }

        @Override
        public void onErrorLooperRunningAlready() {
            Log.e(TAG, "Looper already running!");
        }

        @Override
        public void onDeviceNotFound() {
            if (sUsbController != null) {
                sUsbController.stop();
                sUsbController = null;
                //connectionListener.handler = null;
            }
        }
    };

    BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                //Toast.makeText(context, device.getVendorId() + " | " + device.getProductId(), Toast.LENGTH_SHORT).show();
                if (device.getVendorId() == Constants.DEVICE_VENDOR_ID && device.getProductId() == Constants.DEVICE_PRODUCT_ID) {
                    setDeviceConnect(true);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent
                        .getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device.getVendorId() == Constants.DEVICE_VENDOR_ID && device.getProductId() == Constants.DEVICE_PRODUCT_ID) {
                    setDeviceConnect(false);
                }
            }
        }
    };

    public static String milliToString(long millis) {
        long hrs = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        long min = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long sec = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        long mls = millis % 1000;
        String toRet = String.format(Locale.ENGLISH, "%02d:%02d:%02d.%03d", hrs, min, sec, mls);
        return toRet;
    }

    public static Handler DeviceMsgHandler = new Handler(Looper.myLooper()) {
        public void handleMessage(Message msg) {
            int iChannel = msg.arg1;
            selectedChannel = iChannel;

            switch (msg.what) {
                case Constants.AD800_LINE_STATUS:
                    switch (msg.arg2) {
                        case Constants.CHANNELSTATE_POWEROFF:
                            UsbHelper.channelList.get(iChannel).LineStatus = "Disconnect";
                            Log.e(TAG, "---------handleMessage: CHANNELSTATE_POWEROFF");
                            break;
                        case Constants.CHANNELSTATE_IDLE:
                            Log.e(TAG, "---------handleMessage: CHANNELSTATE_IDLE");
                            if (!UsbHelper.channelList.get(iChannel).CallerId.isEmpty()) {

//                                SharedPrefManager.addNewCall(context,new CallerHistoryModel(channelList.get(iChannel).CallerId, System.currentTimeMillis() + "",null));
                            }
                            channelList.get(iChannel).LineStatus = "Idle";            //hook on
                            channelList.get(iChannel).CallerId = "";
                            channelList.get(iChannel).Dtmf = "";
                            callingStatus = 0;
                            sendBroadCast(0);
                            break;
                        case Constants.CHANNELSTATE_PICKUP:
                            channelList.get(iChannel).LineStatus = "Dialing";
                            Log.e(TAG, "---------handleMessage: CHANNELSTATE_PICKUP");//hook off
                            break;
                        case Constants.CHANNELSTATE_RINGON:
                            Log.e(TAG, "---------handleMessage: CHANNELSTATE_RINGON");
                            Log.e(TAG, "---------handleMessage: ringOn: " + channelList.get(iChannel).CallerId);
                            channelList.get(iChannel).LineStatus = "Ring On";//ring
                            if (callingStatus != 1) {
                                sendBroadCast(1);
                            }
                            break;
                        case Constants.CHANNELSTATE_RINGOFF:
                            Log.e(TAG, "---------handleMessage: CHANNELSTATE_RINGOFF");
                            Log.e(TAG, "---------handleMessage: ringOff: " + channelList.get(iChannel).CallerId);
                            channelList.get(iChannel).LineStatus = "Ring Off";
                            sendBroadCast(1);
                            break;
                        case Constants.CHANNELSTATE_ANSWER:
                            Log.e(TAG, "---------handleMessage: CHANNELSTATE_ANSWER");
                            channelList.get(iChannel).LineStatus = "Incoming Call";  //Answer
//                            if (callingStatus != 1) sendBroadCast(1);
                            //Toast.makeText(context, UsbHelper.channelList.get(selectedChannel).LineStatus, Toast.LENGTH_SHORT).show();
                            sendBroadCast(1);
                            break;
                        case Constants.CHANNELSTATE_OUTGOING:
                            channelList.get(iChannel).LineStatus = "Outgoing Call";  //	hook off
                            break;
                        default:
                            break;
                    }
                    break;
                case Constants.AD800_LINE_VOLTAGE:            //TheApp.channelList.get(iChannel).LineVoltage
                    int oldVoltage = UsbHelper.channelList.get(iChannel).LineVoltage;
                    int newVoltage = msg.arg2;
                    if (oldVoltage == 0 || Math.abs(oldVoltage - newVoltage) * 100 / oldVoltage >= 10)
                        UsbHelper.channelList.get(iChannel).LineVoltage = msg.arg2;
                    else return;
                    break;
                case Constants.AD800_DEVICE_CONNECTION:
                case Constants.AD800_LINE_POLARITY:
                case Constants.AD800_LINE_CALLERID:
                    Log.e(TAG, "-------------handleMessage: AD800_LINE_CALLERID: " + channelList.get(selectedChannel).CallerId);
                    sendBroadCast(1);
                    break;
                case Constants.AD800_LINE_DTMF:
                case Constants.AD800_REC_DATA:
                case Constants.AD800_PLAY_FINISHED:
                case Constants.AD800_VOICETRIGGER:
                case Constants.AD800_BUSYTONE:
                case Constants.AD800_DTMF_FINISHED:
                    break;
            }
        }
    };

    public static void sendBroadCast(int status) {
        callingStatus = status;
        Intent i = new Intent("PHONE_CALL");
        i.putExtra("Number", UsbHelper.channelList.get(selectedChannel).CallerId);
        i.putExtra("LineStatus", UsbHelper.channelList.get(selectedChannel).LineStatus);
        i.putExtra("CallerId", UsbHelper.channelList.get(selectedChannel).CallerId);
        i.putExtra("state", status);
        //Toast.makeText(context, UsbHelper.channelList.get(selectedChannel).CallerId, Toast.LENGTH_SHORT).show();
        context.sendBroadcast(i);
        if (status == 1) {
            String lineStatus = "";
            if (UsbHelper.channelList.get(selectedChannel).LineStatus.equals("Incoming Call")){
                lineStatus = "answer";
            }else {
                lineStatus = "incoming";
            }
            Map<String, String> map = new HashMap<>();
            map.put("call_state", lineStatus);
            map.put("phone", UsbHelper.channelList.get(selectedChannel).CallerId.replaceAll("[^\\d.]", ""));
            //Toast.makeText(context, UsbHelper.channelList.get(selectedChannel).LineStatus, Toast.LENGTH_SHORT).show();
            callingListener.success(map);
        } else if (status == 0) {
            Map<String, String> map = new HashMap<>();
            map.put("call_state", "idle");
            map.put("phone", UsbHelper.channelList.get(selectedChannel).CallerId.replaceAll("[^\\d.]", ""));
            callingListener.success(map);
        } else {
            callingListener.error("400", "Error", "Calling error");
        }
    }
}
