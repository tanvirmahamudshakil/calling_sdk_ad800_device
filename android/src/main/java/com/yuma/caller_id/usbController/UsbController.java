/*
 * UsbController.java
 * This file is part of UsbController
 *
 * Copyright (C) 2012 - Manuel Di Cerbo
 *
 * UsbController is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * UsbController is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with UsbController. If not, see <http://www.gnu.org/licenses/>.
 */
package com.yuma.caller_id.usbController;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Message;

import com.yuma.caller_id.utils.ADPCM_STATE;
import com.yuma.caller_id.utils.BytesTransUtil;
import com.yuma.caller_id.utils.Constants;
import com.yuma.caller_id.utils.DtmfData;
import com.yuma.caller_id.utils.FileLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * UsbController.java
 *
 * @author suju
 */
public class UsbController {

    public final static String TAG = "USBController";
    private final Context mApplicationContext;
    private final UsbManager mUsbManager;
    private final IUsbConnectionHandler mConnectionHandler;
    public List<DeviceID> devices;
    protected static final String ACTION_USB_PERMISSION = "ch.serverbox.android.USB";

    private UsbDevice device;                                        //usb device handler
    private UsbDeviceConnection conn;                                //usb device connection handler
    private UsbInterface usbInf;                                    //usb interface
    //Endpoints are the channels for sending and receiving data over USB.
    private UsbEndpoint epIN = null;                                //usb in endpoint
    private UsbEndpoint epOUT = null;                                //usb out endpoint

    public List<byte[]> m_ReadBuffList = new ArrayList<byte[]>();            //read buffer list from usb device
    public List<byte[]> m_WriteBuffList = new ArrayList<byte[]>();            //write buffer list to usb device

    private static final Object[] sWriteLock = new Object[]{};
    private static final Object[] sReadLock = new Object[]{};

    public byte m_DataPackIndex = 0;                                //frame index to device
    private int m_MemBuffLen = 0;
    private byte[] m_MemBuffer = new byte[256];                        //EEPROM buffer from device

    private UsbReadRunnable mReadLoop;                                // Thread loop for reading data from device
    private UsbWriteRunnable mWriteLoop;                            // Thread loop for writing data to device
    private UsbRunnable mLoop;                                        // Thread loop to analyze reading buffers
    private CountRunnable mCountLoop;                                // Thread loop for timer count
    private Thread mUsbThread, mUsbReadThread, mUsbWriteThread, mCountThread;

    private boolean mStop = false;                                    // Thread stop flag

    /*
     * 	 Init usbcontroller
     *
     */
    public UsbController(Context parentActivity,
                         IUsbConnectionHandler connectionHandler, List<DeviceID> dId) {
        mApplicationContext = parentActivity.getApplicationContext();
        mConnectionHandler = connectionHandler;
        mUsbManager = (UsbManager) mApplicationContext
                .getSystemService(Context.USB_SERVICE);
        devices = dId;
        init();
    }

    /*
     *  calls enumberate function
     */
    private void init() {
        enumerate(new IPermissionListener() {
            @Override
            public void onPermissionDenied(UsbDevice d) {
                UsbManager usbman = (UsbManager) mApplicationContext
                        .getSystemService(Context.USB_SERVICE);
                PendingIntent pi = PendingIntent.getBroadcast(
                        mApplicationContext, 0, new Intent(
                                ACTION_USB_PERMISSION), 0);
                //register permission receiver
                mApplicationContext.registerReceiver(mPermissionReceiver,
                        new IntentFilter(ACTION_USB_PERMISSION));
                //request usb permission
                usbman.requestPermission(d, pi);
            }
        });
    }

    /*
     * 	enumberation
     *  search connected usb device for product id and vendor id and request permission, start main process handler
     */
    private void enumerate(IPermissionListener listener) {
        l("enumerating");
        HashMap<String, UsbDevice> devlist = mUsbManager.getDeviceList();
        Iterator<UsbDevice> deviter = devlist.values().iterator();
        while (deviter.hasNext()) {
            UsbDevice d = deviter.next();
            l("Found device: "
                    + String.format("%04X:%04X", d.getVendorId(),
                    d.getProductId()));
            for (DeviceID dId : devices) {
                if (d.getVendorId() == dId.VID && d.getProductId() == dId.PID) {
                    l("Device under: " + d.getDeviceName());
                    if (!mUsbManager.hasPermission(d))
                        listener.onPermissionDenied(d);
                    else {

                        startHandler(d);
                        return;
                    }
                    break;
                }
            }

        }
        l("no more devices found");
        mConnectionHandler.onDeviceNotFound();
    }

    /*
     * Define broadcast permission receiver.
     *
     */
    private BroadcastReceiver mPermissionReceiver = new PermissionReceiver(
            new IPermissionListener() {
                @Override
                public void onPermissionDenied(UsbDevice d) {
                    l("Permission denied on " + d.getDeviceId());
                }
            });

    private static interface IPermissionListener {
        void onPermissionDenied(UsbDevice d);
    }

    /*
     *  Usb permission receiver
     *  When permission is granted, main handler is started.
     */
    private class PermissionReceiver extends BroadcastReceiver {
        private final IPermissionListener mPermissionListener;

        public PermissionReceiver(IPermissionListener permissionListener) {
            mPermissionListener = permissionListener;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mApplicationContext.unregisterReceiver(this);
            if (intent.getAction().equals(ACTION_USB_PERMISSION)) {
                if (!intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    mPermissionListener.onPermissionDenied((UsbDevice) intent
                            .getParcelableExtra(UsbManager.EXTRA_DEVICE));
                } else {
                    l("Permission granted");
                    UsbDevice dev = (UsbDevice) intent
                            .getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (dev != null) {
                        for (DeviceID dId : devices) {
                            if (dev.getVendorId() == dId.VID
                                    && dev.getProductId() == dId.PID) {
                                startHandler(dev);                                            //start main process handler
                            }
                        }

                    } else {
                        e("device not present!");
                    }
                }
            }
        }
    }

    /*
     * Usb device open is performed.
     * Get usb device connection,interface and in/out endpoint.
     * It's performed read and write by in/out endpoint.
     */
    private void UsbDeviceOpen() {
        try {
            conn = mUsbManager.openDevice(device);
            usbInf = device.getInterface(device.getInterfaceCount() - 1/*1*/);
            if (!conn.claimInterface(usbInf, true)) {
                return;
            }
            e("Endpoint:0x81, Interrupt, Input");
            e("Endpoint:0x02, Interrupt, Output");
            usbInf = device.getInterface(device.getInterfaceCount() - 1/*1*/);
            for (int i = 0; i < usbInf.getEndpointCount(); i++) {
                if (usbInf.getEndpoint(i).getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    if (usbInf.getEndpoint(i).getDirection() == UsbConstants.USB_DIR_IN)
                        epIN = usbInf.getEndpoint(i);
                    else
                        epOUT = usbInf.getEndpoint(i);
                }
            }
        } catch (Exception e) {
            e("UsbEndpointInit " + e.getLocalizedMessage());
        }
    }

    /*
     * Stop process thread.
     *
     */
    public void stop() {
        try {
            mApplicationContext.unregisterReceiver(mPermissionReceiver);
        } catch (IllegalArgumentException e) {
        }
        ;//bravo
        mStop = true;
//		l("stop starting");
        l("stop");
        Sleep(10);
        if (mStop) {
            mLoop = null;
            mUsbThread = null;

            mReadLoop = null;
            mWriteLoop = null;
            mUsbReadThread = null;
            mUsbWriteThread = null;

            mCountLoop = null;
            mCountThread = null;
            mStop = false;
        }
    }

    /*
     *  Start process thread.
     */
    private void startHandler(UsbDevice d) {
        device = d;
        l("start handler");
        try {
            if (mLoop != null) {
                mStop = false;
                mConnectionHandler.onErrorLooperRunningAlready();
                return;
            }
            mLoop = null;
            mUsbThread = null;

            mReadLoop = null;
            mWriteLoop = null;
            mUsbReadThread = null;
            mUsbWriteThread = null;

            mCountLoop = null;
            mCountThread = null;
            mStop = false;

            UsbDeviceOpen();
            if (mStop) return;
            GetSnAndVersion();

            mReadLoop = new UsbReadRunnable();
            mWriteLoop = new UsbWriteRunnable();
            mLoop = new UsbRunnable();
            mCountLoop = new CountRunnable();

            mUsbReadThread = new Thread(mReadLoop);
            mUsbReadThread.setPriority(Thread.MAX_PRIORITY);
            mUsbReadThread.start();

            mUsbWriteThread = new Thread(mWriteLoop);
            mUsbWriteThread.setPriority(Thread.MAX_PRIORITY);
            mUsbWriteThread.start();

            mUsbThread = new Thread(mLoop);
            mUsbThread.setPriority(Thread.MAX_PRIORITY);
            mUsbThread.start();

            mCountThread = new Thread(mCountLoop);
            mCountThread.setPriority(Thread.MAX_PRIORITY);
            mCountThread.start();


            /*
             *  Display first time line status
             */
            for (int i = 0; i < 8; i++) {
                if (mApplicationContext != null) {
                    Message msg = new Message();
                    msg.what = Constants.AD800_LINE_STATUS;
                    msg.arg1 = i;
                    msg.arg2 = UsbHelper.channelList.get(i).m_State;
                    UsbHelper.DeviceMsgHandler.sendMessage(msg);
                }
            }

            l("handler init end");
        } catch (Exception e) {
            e(e);
        }
    }

    /**
     * @return true if there are any data in the queue to be read.
     */
    public boolean IsThereAnyReceivedData() {
        synchronized (sReadLock) {
            return !m_ReadBuffList.isEmpty();
        }
    }

    /**
     * Queue the data from the read queue.
     *
     * @return queued data.
     */
    public byte[] GetReceivedDataFromQueue() {
        try {
            synchronized (sReadLock) {
                byte[] res = new byte[64];
                res = m_ReadBuffList.get(0);
                m_ReadBuffList.remove(0);
                return res;
            }
        } catch (Exception e) {
            e("GetReceivedDataFromQueue exception " + e.getLocalizedMessage());
            m_ReadBuffList.remove(0);
            return null;
        }
    }

    /**
     * @return true if there are any data in the queue to be read.
     */
    public boolean IsThereAnySentData() {
        synchronized (sWriteLock) {
            return !m_WriteBuffList.isEmpty();
        }
    }

    /**
     * Queue the data from the read queue.
     *
     * @return queued data.
     */
    public byte[] GetSentDataFromQueue() {
        try {
            synchronized (sWriteLock) {
                byte[] res = new byte[64];
                res = m_WriteBuffList.get(0);
                m_WriteBuffList.remove(0);
                return res;
            }
        } catch (Exception e) {
            e("GetWriteDataFromQueue exception " + e.getLocalizedMessage());
            m_WriteBuffList.remove(0);
            return null;
        }
    }

    /*
     *  Thread loop for reading data from device
     *  Reading buffers is added to m_ReadBuffList
     */
    private class UsbReadRunnable implements Runnable {

        @Override
        public void run() {
            try {
                while (!mStop) {
                    try {
                        byte[] mRecvBuffer = new byte[64];
                        int p = conn.bulkTransfer(epIN, mRecvBuffer, 64, 100);
                        if (mStop) break;

                        if (p > 0 && mRecvBuffer[0] == Constants.HEADER_IN && mRecvBuffer[1] > 3) {
                            m_ReadBuffList.add(mRecvBuffer);
                        }
                        //Sleep(1);
                    } catch (Exception e) {
                        e("UsbReadRunnable Exception:" + e.getLocalizedMessage());
                    }
                }
                conn.close();
                conn.releaseInterface(usbInf);
                mConnectionHandler.onUsbStopped();
            } catch (Exception e) {
                e("UsbReadRunnable out Exception:" + e.getLocalizedMessage());
            }
        }

    }

    ;

    /*
     *  Thread loop for writing data to device
     *  Writing buffers is added to m_WriteBuffList
     */
    private class UsbWriteRunnable implements Runnable {
        int FreeTime = 0;

        @Override
        public void run() {
            FreeTime = 0;
            boolean bOn = true;
            try {
                while (!mStop) {
                    try {
                        if (!IsThereAnySentData()) {
                            FreeTime++;
                            if (FreeTime >= 800) {
                                FreeTime = 0;
                                //SendLedControlCmd((byte)0,bOn);
                                SendDeviceCheckControlCmd();
                                bOn = !bOn;
                            }
                            Sleep(1);
                            continue;
                        }

                        byte[] sendBuff = GetSentDataFromQueue();
                        if (sendBuff == null) {
                            Sleep(1);
                            continue;
                        }
                        int p = conn.bulkTransfer(epOUT, sendBuff, 64, 100);
                    } catch (Exception e) {
                        e("UsbWriteRunnable Exception:" + e.getLocalizedMessage());
                    }
                }
                conn.close();
                conn.releaseInterface(usbInf);
                mConnectionHandler.onUsbStopped();
            } catch (Exception e) {
                e("UsbWriteRunnable out Exception:" + e.getLocalizedMessage());
            }
        }
    }

    ;

    /*
     *  Thread loop to analyze reading buffers
     *  Process voltage,control ack command, fsk and dtmf frame
     */
    private class UsbRunnable implements Runnable {
        @Override
        public void run() {
            int iLen = 0;
            int iPos = 0;
            int iChannel = 0;
            try {
                while (!mStop) {
                    try {
                        try {
                            if (!IsThereAnyReceivedData()) {
                                Sleep(1);
                                continue;
                            }
                        } catch (Exception e) {
                            e("UsbRunnable IsThereAnyReceivedData");
                            Sleep(1);
                            continue;
                        }
                        byte[] readBuff = null;
                        try {
                            readBuff = GetReceivedDataFromQueue();
                            if (readBuff == null) {
                                Sleep(1);
                                continue;
                            }
                        } catch (Exception e) {
                            e("UsbRunnable Poll");
                            Sleep(1);
                            continue;
                        }

                        try {
                            iLen = readBuff[1] - 4;
                            iChannel = readBuff[3] & 0x7F;
                            iPos = 6;

                            if (iChannel < 0 || iChannel >= 8 || iLen < 0) {
                                continue;
                            }
                        } catch (Exception e) {
                            e("UsbRunnable Middle");
                        }
                        if (mStop) break;

                        //get voltage 
                        try {
                            UsbHelper.channelList.get(iChannel).GetVoltage(((int) readBuff[4] * 256 + readBuff[5]) & 0xFFFF);
                        } catch (Exception e) {
                            e("UsbRunnable Voltage");
                        }
                        try {
                            while (iLen > 0) {
                                byte szPacketLen = readBuff[iPos + 1];

                                if (szPacketLen < iLen) {
                                    AnalyseCommand(iChannel, readBuff, iPos, szPacketLen + 2);
                                } else {
                                    break;
                                }

                                iLen -= (szPacketLen + 2);
                                iPos += (szPacketLen + 2);
                            }
                        } catch (Exception e) {
                            e("UsbRunnable Analyze;" + e.getLocalizedMessage());
                        }

                        if ((readBuff[3] & 0x80) != 0) {
                            //FileLog.e("readBuff[3]", readBuff[3]+" "+(readBuff[3] & 0x80));
                            UsbHelper.channelList.get(iChannel).PlayBuffer();
                            UsbHelper.channelList.get(iChannel).PlayBuffer();
                            UsbHelper.channelList.get(iChannel).PlayBuffer();
                            UsbHelper.channelList.get(iChannel).PlayBuffer();
                            boolean bResult = UsbHelper.channelList.get(iChannel).PlayBuffer();
                            if (!bResult) {
                                // ������� ֹͣռ��(����绰���Ҳ��˻�), play finished, linefree							

                            }
                        }

                    } catch (Exception e) {
                        e("UsbRunnable Exception:" + e.getLocalizedMessage());
                    }
                }
                conn.close();
                conn.releaseInterface(usbInf);
                mConnectionHandler.onUsbStopped();
            } catch (Exception e) {
                e("UsbRunnable out Exception:" + e.getLocalizedMessage());
            }
        }

    }

    ;


    /*
     *  Thread loop for timer count
     *  Get average voltage and line status for every phone lines
     */
    private class CountRunnable implements Runnable {
        @Override
        public void run() {
            while (!mStop) {
                Sleep(10);
                for (int i = 0; i < 8; i++) {
                    try {
                        UsbHelper.channelList.get(i).m_CheckVoltageTime += 10;

                        if (Constants.CHANNELSTATE_RINGON == UsbHelper.channelList.get(i).m_State || Constants.CHANNELSTATE_RINGOFF == UsbHelper.channelList.get(i).m_State)
                            UsbHelper.channelList.get(i).m_RingDuration += 10;
                        if (Constants.CHANNELSTATE_ANSWER == UsbHelper.channelList.get(i).m_State || Constants.CHANNELSTATE_OUTGOING == UsbHelper.channelList.get(i).m_State)
                            UsbHelper.channelList.get(i).m_CallDuration += 10;

                        if (UsbHelper.channelList.get(i).m_CheckVoltageTime >= 100 && UsbHelper.channelList.get(i).m_VoltageIndex > 0) {
                            int Voltage1 = 0;
                            int Voltage2 = 0;
                            boolean bCheckRing = false;

                            UsbHelper.channelList.get(i).m_RingOn1 = 0;
                            UsbHelper.channelList.get(i).m_RingOn2 = 0;

                            Voltage1 = UsbHelper.channelList.get(i).m_VoltageValue / UsbHelper.channelList.get(i).m_VoltageIndex;
                            Voltage2 = 0 - Voltage1;

                            if (UsbHelper.context != null) {
                                Message msg = new Message();
                                msg.what = Constants.AD800_LINE_VOLTAGE;  //STATE_EVENT
                                msg.arg1 = i;
                                msg.arg2 = Voltage1;
                                UsbHelper.DeviceMsgHandler.sendMessage(msg);
                            }

                            if (Constants.CHANNELSTATE_POWEROFF == UsbHelper.channelList.get(i).m_State
                                    || Constants.CHANNELSTATE_IDLE == UsbHelper.channelList.get(i).m_State
                                    || Constants.CHANNELSTATE_RINGON == UsbHelper.channelList.get(i).m_State
                                    || Constants.CHANNELSTATE_RINGOFF == UsbHelper.channelList.get(i).m_State) {
                                bCheckRing = true;
                            }

                            if (bCheckRing) {
                                for (int j = 0; i < UsbHelper.channelList.get(i).m_VoltageIndex && j < 256; j++) {
                                    if (UsbHelper.channelList.get(i).m_VoltageArray[j] >= 24) {
                                        UsbHelper.channelList.get(i).m_RingOn1++;
                                    }

                                    if (UsbHelper.channelList.get(i).m_VoltageArray[j] <= -24) {
                                        UsbHelper.channelList.get(i).m_RingOn2++;
                                    }

                                    UsbHelper.channelList.get(i).m_VoltageArray[j] = 0;
                                }
                            }

                            UsbHelper.channelList.get(i).m_CheckVoltageTime = 0;
                            UsbHelper.channelList.get(i).m_VoltageValue = 0;
                            UsbHelper.channelList.get(i).m_VoltageIndex = 0;

                            if (bCheckRing) {

                                if (UsbHelper.channelList.get(i).m_RingOn1 >= 2 && UsbHelper.channelList.get(i).m_RingOn2 >= 2) {
                                    // ��⵽�����ź�
                                    if (!UsbHelper.channelList.get(i).m_bCheckRingOn) {
                                        UsbHelper.channelList.get(i).m_bCheckRingOn = true;
                                        UsbHelper.channelList.get(i).m_CheckRingOnTime = 0;
                                    }

                                    if (UsbHelper.channelList.get(i).m_bCheckRingOn) {
                                        UsbHelper.channelList.get(i).m_CheckRingOnTime += 100; // 100 ms �ж�һ�� ��������� 100ms ��һ���汾���������

                                        if (UsbHelper.channelList.get(i).m_CheckRingOnTime >= 200) {
                                            // ��⵽�����ź�
                                            UsbHelper.channelList.get(i).m_bCheckIdle = false;
                                            UsbHelper.channelList.get(i).m_CheckIdleTime = 0;

                                            UsbHelper.channelList.get(i).m_bCheckPowerOff = false;
                                            UsbHelper.channelList.get(i).m_CheckPowerOffTime = 0;

                                            UsbHelper.channelList.get(i).m_bCheckHookOff = false;
                                            UsbHelper.channelList.get(i).m_CheckHookOffTime = 0;

                                            UsbHelper.channelList.get(i).m_bCheckRingOn = false;
                                            UsbHelper.channelList.get(i).m_CheckRingOnTime = 0;

                                            UsbHelper.channelList.get(i).m_bInbound = true;
                                            UsbHelper.channelList.get(i).m_RingCnt++;    // ��⵽һ������

                                            if (Constants.CHANNELSTATE_RINGON != UsbHelper.channelList.get(i).m_State) {
                                                UsbHelper.channelList.get(i).m_State = Constants.CHANNELSTATE_RINGON;
                                                if (UsbHelper.context != null) {
                                                    Message msg = new Message();
                                                    msg.what = Constants.AD800_LINE_STATUS;  //STATE_EVENT
                                                    msg.arg1 = i;
                                                    msg.arg2 = UsbHelper.channelList.get(i).m_State;
                                                    UsbHelper.DeviceMsgHandler.sendMessage(msg);
                                                }
                                            }
                                        }
                                    }
                                    continue;
                                } else {
                                }
                            }

                            if (Constants.CHANNELSTATE_RINGON == UsbHelper.channelList.get(i).m_State) {
                                if (UsbHelper.channelList.get(i).m_RingOn1 >= 2 || UsbHelper.channelList.get(i).m_RingOn2 >= 2) {
                                    if (Constants.CHANNELSTATE_RINGOFF != UsbHelper.channelList.get(i).m_State) {
                                        UsbHelper.channelList.get(i).m_RingOffTime = 0;
                                        UsbHelper.channelList.get(i).m_State = Constants.CHANNELSTATE_RINGOFF;
                                        if (UsbHelper.context != null) {
                                            Message msg = new Message();
                                            msg.what = Constants.AD800_LINE_STATUS;  //STATE_EVENT
                                            msg.arg1 = i;
                                            msg.arg2 = UsbHelper.channelList.get(i).m_State;
                                            UsbHelper.DeviceMsgHandler.sendMessage(msg);
                                        }
                                    }
                                    continue;
                                }
                            }


                            if (Constants.CHANNELSTATE_RINGOFF == UsbHelper.channelList.get(i).m_State) {
                                UsbHelper.channelList.get(i).m_RingOffTime += 100;
                                if (UsbHelper.channelList.get(i).m_RingOffTime >= 3700) {        //after 4.5second
                                    if (!UsbHelper.channelList.get(i).m_bCheckIdle) {
                                        UsbHelper.channelList.get(i).m_bCheckIdle = true;
                                        UsbHelper.channelList.get(i).m_CheckIdleTime = 0;
                                    }
                                }
                            }

                            if (Voltage1 <= 3) {
                                // ���ڶ��߷�Χ(û�в���绰��) ���� 3����Ϊû��״̬����
                                if (!UsbHelper.channelList.get(i).m_bCheckPowerOff) {
                                    UsbHelper.channelList.get(i).m_bCheckPowerOff = true;
                                    UsbHelper.channelList.get(i).m_CheckPowerOffTime = 0;
                                }
                            } else if (Voltage1 > 3 && Voltage1 < 24) {
                                // ���������Χ ���� 200ms ��Ϊ״̬����
                                if (!UsbHelper.channelList.get(i).m_bCheckHookOff) {
                                    UsbHelper.channelList.get(i).m_bCheckHookOff = true;
                                    UsbHelper.channelList.get(i).m_CheckHookOffTime = 0;
                                }
                            } else if (Voltage1 >= 24 && Constants.CHANNELSTATE_RINGOFF != UsbHelper.channelList.get(i).m_State) {
                                // ���ڹһ���Χ ����1����Ϊ״̬����
                                if (!UsbHelper.channelList.get(i).m_bCheckIdle) {
                                    UsbHelper.channelList.get(i).m_bCheckIdle = true;
                                    UsbHelper.channelList.get(i).m_CheckIdleTime = 0;
                                }
                            }

                        }

                        //Power Off Checking
                        if (UsbHelper.channelList.get(i).m_bCheckPowerOff) {
                            UsbHelper.channelList.get(i).m_CheckPowerOffTime += 10;
                            if (UsbHelper.channelList.get(i).m_CheckPowerOffTime >= 3000) {
                                if (Constants.CHANNELSTATE_POWEROFF != UsbHelper.channelList.get(i).m_State) {
                                    try {
                                        FileLog.v("Report", "Line Disconnected");
                                    } catch (Exception e) {
                                        FileLog.v("UsbController", e.getLocalizedMessage());
                                    }
                                    UsbHelper.channelList.get(i).m_State = Constants.CHANNELSTATE_POWEROFF;
                                    if (UsbHelper.context != null) {
                                        Message msg = new Message();
                                        msg.what = Constants.AD800_LINE_STATUS;  //STATE_EVENT
                                        msg.arg1 = i;
                                        msg.arg2 = UsbHelper.channelList.get(i).m_State;
                                        UsbHelper.DeviceMsgHandler.sendMessage(msg);
                                    }

                                    // ��⵽�˿ڶ���
                                    UsbHelper.channelList.get(i).m_bCheckIdle = false;
                                    UsbHelper.channelList.get(i).m_CheckIdleTime = 0;

                                    UsbHelper.channelList.get(i).m_bCheckPowerOff = false;
                                    UsbHelper.channelList.get(i).m_CheckPowerOffTime = 0;

                                    UsbHelper.channelList.get(i).m_bCheckHookOff = false;
                                    UsbHelper.channelList.get(i).m_CheckHookOffTime = 0;

                                    UsbHelper.channelList.get(i).m_bCheckRingOn = false;
                                    UsbHelper.channelList.get(i).m_CheckRingOnTime = 0;
                                } else {
                                }
                            }
                        }


                        //Idle Checking
                        if (UsbHelper.channelList.get(i).m_bCheckIdle) {
                            UsbHelper.channelList.get(i).m_CheckIdleTime += 10;
                            if (UsbHelper.channelList.get(i).m_CheckIdleTime >= 800) {
                                if (Constants.CHANNELSTATE_IDLE != UsbHelper.channelList.get(i).m_State) {
                                    String ringDuration = UsbHelper.milliToString(UsbHelper.channelList.get(i).m_RingDuration);
                                    String callDuration = UsbHelper.milliToString(UsbHelper.channelList.get(i).m_CallDuration);

                                    //report
                                    if (Constants.CHANNELSTATE_POWEROFF == UsbHelper.channelList.get(i).m_State)
                                        FileLog.v("Report", "Line Reconnected");
                                    else if (Constants.CHANNELSTATE_RINGOFF == UsbHelper.channelList.get(i).m_State)
                                        FileLog.v("Report", "Lost Call - Line No:" + (i + 1) + " Caller ID:" + UsbHelper.channelList.get(i).CallerId + " Ring Duration:" + ringDuration);
                                    else if (Constants.CHANNELSTATE_ANSWER == UsbHelper.channelList.get(i).m_State)
                                        FileLog.v("Report", "Incoming Call - Line No:" + (i + 1) + " Caller ID:" + UsbHelper.channelList.get(i).CallerId + " Ring Duration:" + ringDuration + " Call Duration:" + callDuration);
                                    else if (Constants.CHANNELSTATE_OUTGOING == UsbHelper.channelList.get(i).m_State)
                                        FileLog.v("Report", "Outgoing Call - Line No:" + (i + 1) + " Caller ID:" + UsbHelper.channelList.get(i).Dialed + " Call Duration:" + callDuration);
                                    UsbHelper.channelList.get(i).Dialed = "";

                                    UsbHelper.channelList.get(i).m_State = Constants.CHANNELSTATE_IDLE;
                                    if (UsbHelper.context != null) {
                                        Message msg = new Message();
                                        msg.what = Constants.AD800_LINE_STATUS;  //STATE_EVENT
                                        msg.arg1 = i;
                                        msg.arg2 = UsbHelper.channelList.get(i).m_State;
                                        UsbHelper.DeviceMsgHandler.sendMessage(msg);
                                    }


                                    // ��⵽�˿ڹһ�
                                    UsbHelper.channelList.get(i).m_bCheckIdle = false;
                                    UsbHelper.channelList.get(i).m_CheckIdleTime = 0;

                                    UsbHelper.channelList.get(i).m_bCheckPowerOff = false;
                                    UsbHelper.channelList.get(i).m_CheckPowerOffTime = 0;

                                    UsbHelper.channelList.get(i).m_bCheckHookOff = false;
                                    UsbHelper.channelList.get(i).m_CheckHookOffTime = 0;
                                    UsbHelper.channelList.get(i).m_DtmfDetectTime = 0;
                                    UsbHelper.channelList.get(i).m_CallDuration = 0;

                                    UsbHelper.channelList.get(i).m_bCheckRingOn = false;
                                    UsbHelper.channelList.get(i).m_CheckRingOnTime = 0;
                                    UsbHelper.channelList.get(i).m_RingCnt = 0;
                                    UsbHelper.channelList.get(i).m_RingOn1 = 0;
                                    UsbHelper.channelList.get(i).m_RingOn2 = 0;
                                    UsbHelper.channelList.get(i).m_bInbound = false;
                                    UsbHelper.channelList.get(i).m_RingOffTime = 0;
                                    UsbHelper.channelList.get(i).m_RingDuration = 0;

                                    // �������ȥ�����buffer
                                    UsbHelper.channelList.get(i).m_FSKCIDDataSize = 0;
                                    UsbHelper.channelList.get(i).m_iCarry = 0;
                                    UsbHelper.channelList.get(i).m_DtmfSize = 0;

                                    for (int j = 0; j < UsbHelper.channelList.get(i).m_DtmfNum.length; j++)
                                        UsbHelper.channelList.get(i).m_DtmfNum[j] = 0;
                                    for (int j = 0; j < UsbHelper.channelList.get(i).m_szFSKCIDData.length; j++)
                                        UsbHelper.channelList.get(i).m_szFSKCIDData[j] = 0;
                                    for (int j = 0; j < UsbHelper.channelList.get(i).m_FskNum.length; j++)
                                        UsbHelper.channelList.get(i).m_FskNum[j] = 0;
                                } else {
                                }
                            }
                        }

                        //Hook Off Checking
                        if (UsbHelper.channelList.get(i).m_bCheckHookOff) {
                            //If not Incoming call, have dialed and current status is not outgoing call
                            if (!UsbHelper.channelList.get(i).m_bInbound && !UsbHelper.channelList.get(i).Dtmf.isEmpty() && (Constants.CHANNELSTATE_OUTGOING != UsbHelper.channelList.get(i).m_State))
                                UsbHelper.channelList.get(i).m_DtmfDetectTime += 10;

                            UsbHelper.channelList.get(i).m_CheckHookOffTime += 10;
                            if (UsbHelper.channelList.get(i).m_CheckHookOffTime >= 200) {
                                // ��⵽�˿����
                                UsbHelper.channelList.get(i).m_bCheckIdle = false;
                                UsbHelper.channelList.get(i).m_CheckIdleTime = 0;

                                UsbHelper.channelList.get(i).m_bCheckPowerOff = false;
                                UsbHelper.channelList.get(i).m_CheckPowerOffTime = 0;

                                UsbHelper.channelList.get(i).m_bCheckHookOff = false;
                                UsbHelper.channelList.get(i).m_CheckHookOffTime = 0;

                                UsbHelper.channelList.get(i).m_bCheckRingOn = false;
                                UsbHelper.channelList.get(i).m_CheckRingOnTime = 0;
                                UsbHelper.channelList.get(i).m_RingCnt = 0;
                                UsbHelper.channelList.get(i).m_RingOn1 = 0;
                                UsbHelper.channelList.get(i).m_RingOn2 = 0;
                                UsbHelper.channelList.get(i).m_RingOffTime = 0;

                                if (UsbHelper.channelList.get(i).m_bInbound) {
                                    if (Constants.CHANNELSTATE_ANSWER != UsbHelper.channelList.get(i).m_State) {
                                        UsbHelper.channelList.get(i).m_CallDuration = 0;
                                        UsbHelper.channelList.get(i).m_State = Constants.CHANNELSTATE_ANSWER;
                                        if (UsbHelper.context != null) {
                                            Message msg = new Message();
                                            msg.what = Constants.AD800_LINE_STATUS;  //STATE_EVENT
                                            msg.arg1 = i;
                                            msg.arg2 = UsbHelper.channelList.get(i).m_State;
                                            UsbHelper.DeviceMsgHandler.sendMessage(msg);
                                        }
                                    }
                                } else {
                                    if (UsbHelper.channelList.get(i).m_DtmfDetectTime > 4000) {
                                        if (Constants.CHANNELSTATE_OUTGOING != UsbHelper.channelList.get(i).m_State) {
                                            UsbHelper.channelList.get(i).m_State = Constants.CHANNELSTATE_OUTGOING;
                                            UsbHelper.channelList.get(i).m_CallDuration = 0;
                                            UsbHelper.channelList.get(i).Dialed = UsbHelper.channelList.get(i).Dtmf;

                                            if (UsbHelper.context != null) {
                                                Message msg = new Message();
                                                msg.what = Constants.AD800_LINE_STATUS;  //STATE_EVENT
                                                msg.arg1 = i;
                                                msg.arg2 = UsbHelper.channelList.get(i).m_State;
                                                UsbHelper.DeviceMsgHandler.sendMessage(msg);
                                            }
                                        }
                                    } else if (Constants.CHANNELSTATE_PICKUP != UsbHelper.channelList.get(i).m_State) {
                                        UsbHelper.channelList.get(i).m_State = Constants.CHANNELSTATE_PICKUP;
                                        if (UsbHelper.context != null) {
                                            Message msg = new Message();
                                            msg.what = Constants.AD800_LINE_STATUS;  //STATE_EVENT
                                            msg.arg1 = i;
                                            msg.arg2 = UsbHelper.channelList.get(i).m_State;
                                            UsbHelper.DeviceMsgHandler.sendMessage(msg);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        FileLog.v("CountSerivce", "Exception:" + e.getLocalizedMessage());
                    }
                }
            }
        }
    }

    ;

    /*
     *  Analyze Commands
     *  Process control,fsk,dtmf,rec and play commands from device
     */
    private void AnalyseCommand(int iChannel, byte[] pszData, int iPos, int iLen) {
        if (iChannel < 0 || iChannel >= 8) {
            return;
        }

        switch (pszData[iPos]) {
            case Constants.COMMAND_CONTROL:            //control command
//				if(pszData[iPos+1] > 2)
//				{
                if (pszData[iPos + 2] == Constants.DEVICE_CTRL_ACK)
                    AnalyseACKCommand(iChannel, pszData, iPos + 3, pszData[iPos + 1] - 1);
//				}
                break;
            case Constants.COMMAND_FSK:                //FSK data
                UsbHelper.channelList.get(iChannel).FskData(pszData, iPos + 2, pszData[1 + iPos]);
                break;
            case Constants.COMMAND_DTMF:            //DTMF data
                UsbHelper.channelList.get(iChannel).DTMFData(pszData, iPos + 2, pszData[1 + iPos]);
                break;
            case Constants.COMMADN_REC:                //record data
                // pszData[1] = ¼�����ݰ�����+1  adpcm ÿ��block 256bytes, ÿ����4KBBytes
                // pszData[2] = ¼�����ݰ���� 0,1,2,3,4  ֻ�е����Ϊ 4��ʱ�򳤶��� 52 ��ǰ������� ���ȶ��㶨Ϊ 51. 51+51+51+51+52 = 256 BYTE
                // ��ʵ�ʵĿ��������� �������� ������Ժ�æ,��Ӧ��,���ֹ����������. Ϊ��ά��adpcm���ݰ������� ������Ҫ����pszData[2] ������ж϶����ĸ���
                // �Ѷ�ʧ�����ݰ����� 0x00 
                // Ӧ�ó����յ���Ƶ���ݰ�,����ֱ��������߳�������,��Ӧ���ݴ浽�ڴ���,Ȼ����д���ļ�,��ֹ���ﴦ�����ݰ�ʱ�����

                byte LostBuff[] = new byte[64];
                for (int i = 0; i < 64; i++) LostBuff[i] = 0;
                if (UsbHelper.channelList.get(iChannel).m_LastAdPcmIndex != pszData[iPos + 2]) {
                    // ���ݰ���ʧ ���ߵ�һ��¼���������ݰ���������
                    if (UsbHelper.channelList.get(iChannel).m_LastAdPcmIndex < pszData[iPos + 2]) {
                        for (int i = 0; i < pszData[iPos + 2]; i++) {
                            UsbHelper.channelList.get(iChannel).RecBuffer(LostBuff, 0, 51);
                        }
                    } else {
                        for (int i = UsbHelper.channelList.get(iChannel).m_LastAdPcmIndex; i < 5; i++) {
                            if (i != 4) {
                                UsbHelper.channelList.get(iChannel).RecBuffer(LostBuff, 0, 51);
                            } else {
                                UsbHelper.channelList.get(iChannel).RecBuffer(LostBuff, 0, 52);
                            }
                        }

                        for (int i = 0; i < pszData[iPos + 2]; i++) {
                            UsbHelper.channelList.get(iChannel).RecBuffer(LostBuff, 0, 51);
                        }
                    }
                } else {
                    // û�ж���
                }

                // �����յ���¼������
                UsbHelper.channelList.get(iChannel).RecBuffer(pszData, iPos + 3, pszData[iPos + 1] - 1);

                // ��¼���һ�����ݰ����
                UsbHelper.channelList.get(iChannel).m_LastAdPcmIndex = (byte) (pszData[iPos + 2] + 1);

                if (UsbHelper.channelList.get(iChannel).m_LastAdPcmIndex > 4) {
                    UsbHelper.channelList.get(iChannel).m_LastAdPcmIndex = 0;
                }
                break;
            case Constants.COMMAND_PLAY:            //play data

                break;
            default:
                break;
        }
    }

    /*
     *  Analyze ACK command from device after send command to device
     */
    void AnalyseACKCommand(int iChannel, byte[] pszData, int iPos, int iLen) {
        switch (pszData[iPos]) {
            case Constants.DEVICE_CTRL_REC:
                break;
            case Constants.DEVICE_CTRL_STOPREC:
                break;
            case Constants.DEVICE_CTRL_VER: {//03 0b 00 00 b7 60 30 05 00 03 01 01 0f
                UsbHelper.DeviceVer = "";
                for (int i = 1; i < iLen; i++) {
                    if (i == iLen - 1) UsbHelper.DeviceVer += pszData[iPos + i];
                    else UsbHelper.DeviceVer += pszData[iPos + i] + ".";
                }
                if (UsbHelper.context != null) {
                    Message msg = new Message();
                    msg.what = Constants.AD800_DEVICE_CONNECTION;
                    UsbHelper.DeviceMsgHandler.sendMessage(msg);
                }
            }
            break;
            case Constants.DEVICE_CTRL_RECVOL:
                break;
            case Constants.DEVICE_CTRL_PLAYVOL:
                break;
            case Constants.DEVICE_CTRL_PLAY:

                UsbHelper.channelList.get(iChannel).PlayBuffer();
                UsbHelper.channelList.get(iChannel).PlayBuffer();
                UsbHelper.channelList.get(iChannel).PlayBuffer();
                UsbHelper.channelList.get(iChannel).PlayBuffer();
                boolean bResult = UsbHelper.channelList.get(iChannel).PlayBuffer();
                if (!bResult) {
                    // ������� ֹͣռ��(����绰���Ҳ��˻�)
                    SendHangUpCommand(iChannel);
                }
                break;
            case Constants.DEVICE_CTRL_STOPPLAY:
                break;
            case Constants.DEVICE_CTRL_VOCACK:
                break;
            case Constants.DEVICE_CTRL_DISPHONE:
                break;
            case Constants.DEVICE_CTRL_LINEBUSY:
                break;
            case Constants.DEVICE_CTRL_AGC:
                break;
            case Constants.DEVICE_CTRL_WRITEEPROM:
                break;
            case Constants.DEVICE_CTRL_READEPROM: {
                int iDataLen = iLen - 1;
                //e("ReadEprom:"+iDataLen);

                if (iDataLen < 1) {
                    break;
                }

                if (m_MemBuffLen + iDataLen <= Constants.EEPROMDATA_SIZE) //
                {
                    System.arraycopy(pszData, iPos + 1, m_MemBuffer, m_MemBuffLen, iDataLen);
                    m_MemBuffLen += iDataLen;
                }

                if (m_MemBuffLen >= Constants.EEPROMDATA_SIZE)  //Constants.EEPROMDATA_SIZE
                {
                    int m_SN = 0;
                    m_MemBuffLen = 0;

                    m_SN = m_MemBuffer[Constants.DEVSN_ADDR] & 0xff;
                    m_SN |= (m_MemBuffer[Constants.DEVSN_ADDR + 1] & 0xff) << 8;
                    m_SN |= (m_MemBuffer[Constants.DEVSN_ADDR + 2] & 0xff) << 16;
                    m_SN |= (m_MemBuffer[Constants.DEVSN_ADDR + 3] & 0xff) << 24;
                    if (m_SN != -1) {
                        UsbHelper.DeviceSN = String.format("%08d", m_SN);
                        if (mApplicationContext != null) {
                            Message msg = new Message();
                            msg.what = Constants.AD800_DEVICE_CONNECTION;
                            UsbHelper.DeviceMsgHandler.sendMessage(msg);
                        }
                    }
                }
            }
            break;
            case (byte) Constants.DEVICE_CTRL_CHECK:
                break;
            default:
                break;
        }
    }

    /*
     * Read software version and device serail number
     */
    private void GetSnAndVersion() {
//		SendVersionControlCmd();		
//		SendReadEPROMControlCmd();
        int cnt = 0;
        while ((UsbHelper.DeviceSN == "" || UsbHelper.DeviceVer == "") && cnt++ < 1) {
            for (m_DataPackIndex = 0; m_DataPackIndex < 22; m_DataPackIndex++) {
                if (mStop) return;
                try {
                    byte[] mRecvBuffer = new byte[64 * 8];
                    int p = conn.bulkTransfer(epIN, mRecvBuffer, 64 * 8, 100);
                    if (p > 0) {
                        if (mRecvBuffer[0] == Constants.HEADER_IN && mRecvBuffer[1] > 4)
                            AnalyseCommand(0, mRecvBuffer, 6, mRecvBuffer[7] + 2);

                        byte[] sendByte = new byte[64];
                        for (int i = 0; i < 64; i++) sendByte[i] = 0;
                        if (m_DataPackIndex == 0) {
                            sendByte[0] = 0x02;
                            sendByte[1] = 0x07;
                            sendByte[6] = 0x30;
                            sendByte[7] = 0x01;
                            sendByte[8] = 0x03;
                        } else if (m_DataPackIndex == 1) {
                            sendByte[0] = 0x02;
                            sendByte[1] = 0x09;
                            sendByte[2] = 0x01;
                            sendByte[6] = 0x30;
                            sendByte[7] = 0x03;
                            sendByte[8] = 0x11;
                            sendByte[9] = 0x00;
                            sendByte[10] = 0x1e;
                        } else if (m_DataPackIndex == 2) {
                            sendByte[0] = 0x02;
                            sendByte[1] = 0x09;
                            sendByte[2] = 0x02;
                            sendByte[6] = 0x30;
                            sendByte[7] = 0x03;
                            sendByte[8] = 0x11;
                            sendByte[9] = 0x1e;
                            sendByte[10] = 0x1e;
                        } else if (m_DataPackIndex == 3) {
                            sendByte[0] = 0x02;
                            sendByte[1] = 0x09;
                            sendByte[2] = 0x03;
                            sendByte[6] = 0x30;
                            sendByte[7] = 0x03;
                            sendByte[8] = 0x11;
                            sendByte[9] = 0x3c;
                            sendByte[10] = 0x1e;
                        } else if (m_DataPackIndex == 4) {
                            sendByte[0] = 0x02;
                            sendByte[1] = 0x09;
                            sendByte[2] = 0x04;
                            sendByte[6] = 0x30;
                            sendByte[7] = 0x03;
                            sendByte[8] = 0x11;
                            sendByte[9] = 0x5a;
                            sendByte[10] = 0x1e;
                        } else if (m_DataPackIndex == 5) {
                            sendByte[0] = 0x02;
                            sendByte[1] = 0x09;
                            sendByte[2] = 0x054;
                            sendByte[6] = 0x30;
                            sendByte[7] = 0x03;
                            sendByte[8] = 0x11;
                            sendByte[9] = 0x78;
                            sendByte[10] = 0x08;
                        } else if (m_DataPackIndex > 5 && m_DataPackIndex < 22) {
                            if (m_DataPackIndex % 2 == 0) {
                                sendByte[0] = 0x02;
                                sendByte[1] = 0x09;
                                sendByte[2] = (byte) (m_DataPackIndex);
                                sendByte[3] = (byte) ((m_DataPackIndex - 4) / 2 - 1);
                                sendByte[6] = 0x30;
                                sendByte[7] = 0x03;
                                sendByte[8] = 0x05;
                                sendByte[9] = 0x0b;
                                sendByte[10] = 0x00;
                            } else {
                                sendByte[0] = 0x02;
                                sendByte[1] = 0x09;
                                sendByte[2] = (byte) (m_DataPackIndex);
                                sendByte[3] = (byte) ((m_DataPackIndex - 5) / 2 - 1);
                                sendByte[6] = 0x30;
                                sendByte[7] = 0x03;
                                sendByte[8] = 0x04;
                                sendByte[9] = 0x04;
                                sendByte[10] = 0x20;
                            }
                        }
                        conn.bulkTransfer(epOUT, sendByte, 64, 100);
                    }
                } catch (Exception e) {
                    e("transfer:" + e.getLocalizedMessage());
                }

            }
            //e("---------------------transfer-------------------------");
        }
    }


    //---------------------------------------------Commands --------------------------------------------------------
    void SendDataToDevice(int iChannel, byte[] pBuffer, int Length) {
        byte[] pWriteBuff = new byte[64];
        for (int i = 0; i < 64; i++) pWriteBuff[i] = 0;

        pWriteBuff[0] = (byte) 0x02;
        pWriteBuff[1] = (byte) (4 + Length);
        pWriteBuff[2] = m_DataPackIndex;
        pWriteBuff[3] = (byte) (iChannel % 8);
        pWriteBuff[4] = 0x00;
        pWriteBuff[5] = 0x00;

        //System.arraycopy(pWriteBuff, 6, pBuffer, 0, Length);
        for (int i = 0; i < Length; i++) {
            pWriteBuff[6 + i] = pBuffer[i];
        }
        m_WriteBuffList.add(pWriteBuff);
		
/*		String s = "";
		for(int i=0;i<64;i++) {
			s += String.format("%x", pWriteBuff[i]) + " ";
		}
		FileLog.v("SendDataToDevie", s);
*/
        m_DataPackIndex++;
        if (m_DataPackIndex > 0xFF) {
            m_DataPackIndex = 0x00;
        }
    }

    /*
     * Send EPROM Control Commands
     */
    public void SendReadEPROMControlCmd() {
        int i = 0;
        for (i = 0; i + 16 < Constants.EEPROMDATA_SIZE; i += 16) {
            SendDataToDevice(0, new byte[]{Constants.COMMAND_CONTROL, 0x03, Constants.DEVICE_CTRL_READEPROM, (byte) i, 16}, 0x05);
        }

        if (i < Constants.EEPROMDATA_SIZE) {
            SendDataToDevice(0, new byte[]{Constants.COMMAND_CONTROL, 0x03, Constants.DEVICE_CTRL_READEPROM, (byte) i, (byte) (Constants.EEPROMDATA_SIZE - i)}, 0x05);
        }
    }

    /*
     * Send software version read control command
     */
    public void SendVersionControlCmd() {
        byte[] mSendBuffer = new byte[64];
        for (int i = 0; i < 64; i++) mSendBuffer[i] = 0;
        mSendBuffer[0] = Constants.HEADER_OUT;
        mSendBuffer[1] = 0x08;
        mSendBuffer[2] = m_DataPackIndex;
        mSendBuffer[3] = 0x00;
        mSendBuffer[4] = 0x00;
        mSendBuffer[5] = 0x00;
        mSendBuffer[6] = Constants.COMMAND_CONTROL;
        mSendBuffer[7] = 0x01;
        mSendBuffer[8] = (byte) Constants.DEVICE_CTRL_VER;

        m_WriteBuffList.add(mSendBuffer);

        m_DataPackIndex++;
        if (m_DataPackIndex > 0xFF) {
            m_DataPackIndex = 0x00;
        }

    }

    /*
     * Send device check control command
     */
    public void SendDeviceCheckControlCmd() {
        byte[] mSendBuffer = new byte[64];
        for (int i = 0; i < 64; i++) mSendBuffer[i] = 0;
        mSendBuffer[0] = Constants.HEADER_OUT;
        mSendBuffer[1] = 0x07;
        mSendBuffer[2] = m_DataPackIndex;
        mSendBuffer[3] = 0x00;
        mSendBuffer[4] = 0x00;
        mSendBuffer[5] = 0x00;
        mSendBuffer[6] = Constants.COMMAND_CONTROL;
        mSendBuffer[7] = 0x01;
        mSendBuffer[8] = (byte) 0xFF;

        m_WriteBuffList.add(mSendBuffer);

        m_DataPackIndex++;
        if (m_DataPackIndex > 0xFF) {
            m_DataPackIndex = 0x00;
        }
    }


    /*
     * Send Relay on/off control command
     */
    public void SendLedControlCmd(byte Channel, boolean bOn) {
        byte[] mSendBuffer = new byte[64];
        for (int i = 0; i < 64; i++) mSendBuffer[i] = 0;
        mSendBuffer[0] = Constants.HEADER_OUT;
        mSendBuffer[1] = 0x08;
        mSendBuffer[2] = m_DataPackIndex;
        mSendBuffer[3] = (byte) (Channel & 0xFF);
        mSendBuffer[4] = 0x00;
        mSendBuffer[5] = 0x00;
        mSendBuffer[6] = Constants.COMMAND_CONTROL;
        mSendBuffer[7] = 0x01;
        mSendBuffer[8] = Constants.DEVICE_CTRL_DISPHONE;
        mSendBuffer[9] = (byte) (bOn ? 0x01 : 0x00);

        m_WriteBuffList.add(mSendBuffer);

        m_DataPackIndex++;
        if (m_DataPackIndex > 0xFF) {
            m_DataPackIndex = 0x00;
        }
    }

    public void SetPlayVolume(int iChannel, int Volume) {
        // ���÷�������
        if (iChannel >= 8) {
            return;
        }

        int iVol = (Volume * 256);
        SendDataToDevice(iChannel, new byte[]{Constants.COMMAND_CONTROL, 0x03, Constants.DEVICE_CTRL_PLAYVOL, (byte) ((iVol >> 8) & 0xFF), (byte) (iVol & 0xFF)}, 0x05);
    }

    public void SetRecordVolume(int iChannel, int Volume) {
        // ����¼������
        if (iChannel >= 8) {
            return;
        }

        int iVol = (Volume * 96);
        SendDataToDevice(iChannel, new byte[]{Constants.COMMAND_CONTROL, 0x03, Constants.DEVICE_CTRL_RECVOL, (byte) ((iVol >> 8) & 0xFF), (byte) (iVol & 0xFF)}, 0x05);
    }

    void SetAGC(int iChannel, int Agc) {
        // ����ͨ��AGC ����
        if (iChannel >= 8) {
            return;
        }
        byte a = 0;
        if (Agc != 0) a = 1;
        SendDataToDevice(iChannel, new byte[]{Constants.COMMAND_CONTROL, 0x02, Constants.DEVICE_CTRL_AGC, a}, 0x04);
    }

    public void StartRecord(int iChannel) {
        // ��ʼ¼��
        if (iChannel >= 8) {
            return;
        }

        UsbHelper.channelList.get(iChannel).m_RecLength = 0;
        UsbHelper.channelList.get(iChannel).m_LastAdPcmIndex = 0;
        SendDataToDevice(iChannel, new byte[]{Constants.COMMAND_CONTROL, 0x01, Constants.DEVICE_CTRL_REC}, 0x03);
    }

    public void StopRecord(int iChannel) {
        // ����¼��
        if (iChannel >= 8) {
            return;
        }

        SendDataToDevice(iChannel, new byte[]{Constants.COMMAND_CONTROL, 0x01, Constants.DEVICE_CTRL_STOPREC}, 0x03);
    }

    /*
     *  Send Busy line control command (Line Busy command)
     */
    public void SendPickUpCommand(int iChannel) {

        SendDataToDevice(iChannel, new byte[]{Constants.COMMAND_CONTROL, 0x02, Constants.DEVICE_CTRL_LINEBUSY, 0x01}, 0x04);
    }

    /*
     *  Send Busy line control command (Line Busy command)
     */
    public void SendHangUpCommand(int iChannel) {
        SendDataToDevice(iChannel, new byte[]{Constants.COMMAND_CONTROL, 0x02, Constants.DEVICE_CTRL_LINEBUSY, 0x00}, 0x04);
    }

    /*
     *  Send Relay on control command (Connect Phone)
     */
    public void SendConnectPhoneCommand(int iChannel) {
        SendDataToDevice(iChannel, new byte[]{Constants.COMMAND_CONTROL, 0x02, Constants.DEVICE_CTRL_DISPHONE, 0x00}, 0x04);
    }

    /*
     *  Send Relay off control command (Disconnect Phone)
     */
    public void SendDisconnectPhoneCommand(int iChannel) {
        SendDataToDevice(iChannel, new byte[]{Constants.COMMAND_CONTROL, 0x02, Constants.DEVICE_CTRL_DISPHONE, 0x01}, 0x04);
    }


    // linear pcm16 to ima-adpcm
    void PCM2ADPCM(byte[] pADPCM, int outPos, short[] pPCM, int inPos, int iLen, List<ADPCM_STATE> state) {
        //
        // ��LinearPCM����ת��ΪIMA-ADPCM����
        //
        short[] inp;        /* Input buffer pointer */
        byte[] outp;        /* output buffer pointer */
        int val;            /* Current input sample value */
        int sign;            /* Current adpcm sign bit */
        int delta;            /* Current adpcm output value */
        int diff;            /* Difference between val and valprev */
        int step;            /* Stepsize */
        int valpred;        /* Predicted output value */
        int vpdiff;            /* Current change to valpred */
        int index;            /* Current step change index */
        byte outputbuffer = 0;        /* place to keep previous 4-bit value */
        boolean bufferstep;        /* toggle between outputbuffer/output */

        outp = pADPCM;
        inp = pPCM;

        valpred = state.get(0).valprev;
        index = state.get(0).index;
        step = Constants.stepsizeTable[index];

        bufferstep = true;

        for (; iLen > 0; iLen--) {
            val = inp[inPos++];

            /* Step 1 - compute difference with previous value */
            diff = val - valpred;
            sign = (diff < 0) ? 8 : 0;
            if (sign != 0) diff = (-diff);

            /* Step 2 - Divide and clamp */
            /* Note:
             ** This code *approximately* computes:
             **    delta = diff*4/step;
             **    vpdiff = (delta+0.5)*step/4;
             ** but in shift step bits are dropped. The net result of this is
             ** that even if you have fast mul/div hardware you cannot put it to
             ** good use since the fixup would be too expensive.
             */
            delta = 0;
            vpdiff = (step >> 3);

            if (diff >= step) {
                delta = 4;
                diff -= step;
                vpdiff += step;
            }

            step >>= 1;
            if (diff >= step) {
                delta |= 2;
                diff -= step;
                vpdiff += step;
            }

            step >>= 1;
            if (diff >= step) {
                delta |= 1;
                vpdiff += step;
            }

            /* Step 3 - Update previous value */
            if (sign != 0)
                valpred -= vpdiff;
            else
                valpred += vpdiff;

            /* Step 4 - Clamp previous value to 16 bits */
            if (valpred > 32767)
                valpred = 32767;
            else if (valpred < -32768)
                valpred = -32768;

            /* Step 5 - Assemble value, update index and step values */
            delta |= sign;

            index += Constants.indexTable[delta];
            if (index < 0) index = 0;
            if (index > 88) index = 88;
            step = Constants.stepsizeTable[index];

            /* Step 6 - Output value */
            if (bufferstep) {
                outputbuffer = (byte) (delta & 0x0f);
            } else {
                outp[outPos++] = (byte) (((delta << 4) & 0xf0) | outputbuffer);
            }
            bufferstep = !bufferstep;
        }

        /* Output last step, if needed */
        if (!bufferstep)
            outp[outPos++] = outputbuffer;

        state.get(0).valprev = (short) valpred;
        state.get(0).index = (char) index;
    }

    public void StartPlay(int iChannel) {
        SendDataToDevice(iChannel, new byte[]{Constants.COMMAND_CONTROL, 0x01, Constants.DEVICE_CTRL_PLAY}, 0x3);
    }

    public void StopPlay(int iChannel) {
        SendDataToDevice(iChannel, new byte[]{Constants.COMMAND_CONTROL, 0x01, Constants.DEVICE_CTRL_STOPPLAY}, 0x3);
    }


    public void StartPlayBuffer(int iChannel, byte[] pAudioBuffer, int Length) {
        // ��ʼ����������
        if (iChannel >= 8 || null == pAudioBuffer) {
            return;
        }

        if (null != UsbHelper.channelList.get(iChannel).m_pPlayBuffer) {
            UsbHelper.channelList.get(iChannel).m_pPlayBuffer = null;
        }

        UsbHelper.channelList.get(iChannel).m_pPlayBuffer = new byte[Length + 1];
        if (null == UsbHelper.channelList.get(iChannel).m_pPlayBuffer) {
            return;
        }

        for (int i = 0; i < Length; i++) {
            UsbHelper.channelList.get(iChannel).m_pPlayBuffer[i] = pAudioBuffer[i];
        }
        //System.arraycopy(pAudioBuffer, 0, UsbHelper.channelList.get(iChannel).m_pPlayBuffer, 0, Length);	
        UsbHelper.channelList.get(iChannel).m_PlayBufferLength = Length;    // ��Ҫ���ŵ���Ƶ�ܳ���
        UsbHelper.channelList.get(iChannel).m_PlayCurPosition = 0;            // ��ǰ����λ��
        UsbHelper.channelList.get(iChannel).m_PlayIndex = 0;            // ��ǰ���ŵ���Ƶ���ݰ����
    }

    public void StopPlayBuffer(int iChannel) {
        // ��������������
        if (iChannel >= 8) {
            return;
        }

        UsbHelper.channelList.get(iChannel).m_PlayBufferLength = 0;
        StopPlay(iChannel);
    }


    public short[] convert(char buf[]) {
        byte barr[] = new byte[buf.length];
        for (int i = 0; i < barr.length; i++) barr[i] = (byte) buf[i];
        return BytesTransUtil.getInstance().Bytes2Shorts(barr);
		   /*
		   short shortArr[] = new short[buf.length / 2];
		   int offset = 0;
		   for(int i = 0; i < shortArr.length; i++) {
		      shortArr[i] = (short) ((buf[1 + offset] & 0xFF) | ((buf[0 + offset] & 0xFF) << 8));  
		      offset += 2;
		   }
		   return shortArr;
		   */
    }


    /*
     * 	Send DTMF tons
     */
    public void DialOut(int iChannel, byte[] DialNum) {
        if (null == DialNum) {
            return;
        }

        int DialNumLen = DialNum.length;
        if (DialNumLen <= 0) {
            return;
        }

//		if(DialNumLen == 4) {					
//			char carr[] = new char[]{0x27,0x9b};
//			short sarr[] = convert(carr);						
//			for(int i=0;i<sarr.length;i++)
//				Log.e("test", String.format("%x", sarr[i]));
//			return;
//		}

        if (null != UsbHelper.channelList.get(iChannel).m_pPlayBuffer) {
            UsbHelper.channelList.get(iChannel).m_pPlayBuffer = null;
        }

        int iOnTime = DtmfData.DTMF_0.length;    // 70ms
        int iOffTime = 1910;                    // 119.375ms
        short[] pPCM = null;
        int PCMLen = 0;

        pPCM = new short[(iOnTime + iOffTime) * DialNumLen + 1024];
        if (null == pPCM) {
            return;
        }

        UsbHelper.channelList.get(iChannel).m_pPlayBuffer = new byte[(iOnTime + iOffTime) * DialNumLen + 1024];

        if (null == UsbHelper.channelList.get(iChannel).m_pPlayBuffer) {
            pPCM = null;
            return;
        }

        for (int i = 0; i < (iOnTime + iOffTime) * DialNumLen + 1024; i++) pPCM[i] = 0;
        for (int i = 0; i < (iOnTime + iOffTime) * DialNumLen + 1024; i++)
            UsbHelper.channelList.get(iChannel).m_pPlayBuffer[i] = 0;

        for (int i = 0; i < DialNumLen; i++) {
            switch (DialNum[i]) {
                case '0':
                    System.arraycopy(convert(DtmfData.DTMF_0), 0, pPCM, PCMLen, iOnTime / 2);
                    PCMLen += iOnTime;
                    PCMLen += iOffTime;
                    break;
                case '1':
                    System.arraycopy(convert(DtmfData.DTMF_1), 0, pPCM, PCMLen, iOnTime / 2);
                    PCMLen += iOnTime;
                    PCMLen += iOffTime;
                    break;
                case '2':
                    System.arraycopy(convert(DtmfData.DTMF_2), 0, pPCM, PCMLen, iOnTime / 2);
                    PCMLen += iOnTime;
                    PCMLen += iOffTime;
                    break;
                case '3':
                    System.arraycopy(convert(DtmfData.DTMF_3), 0, pPCM, PCMLen, iOnTime / 2);
                    PCMLen += iOnTime;
                    PCMLen += iOffTime;
                    break;
                case '4':
                    System.arraycopy(convert(DtmfData.DTMF_4), 0, pPCM, PCMLen, iOnTime / 2);
                    PCMLen += iOnTime;
                    PCMLen += iOffTime;
                    break;
                case '5':
                    System.arraycopy(convert(DtmfData.DTMF_5), 0, pPCM, PCMLen, iOnTime / 2);
                    PCMLen += iOnTime;
                    PCMLen += iOffTime;
                    break;
                case '6':
                    System.arraycopy(convert(DtmfData.DTMF_6), 0, pPCM, PCMLen, iOnTime / 2);
                    PCMLen += iOnTime;
                    PCMLen += iOffTime;
                    break;
                case '7':
                    System.arraycopy(convert(DtmfData.DTMF_7), 0, pPCM, PCMLen, iOnTime / 2);
                    PCMLen += iOnTime;
                    PCMLen += iOffTime;
                    break;
                case '8':
                    System.arraycopy(convert(DtmfData.DTMF_8), 0, pPCM, PCMLen, iOnTime / 2);
                    PCMLen += iOnTime;
                    PCMLen += iOffTime;
                    break;
                case '9':
                    System.arraycopy(convert(DtmfData.DTMF_9), 0, pPCM, PCMLen, iOnTime / 2);
                    PCMLen += iOnTime;
                    PCMLen += iOffTime;
                    break;
                case ',':  // ��ͣһ��DTMF������ʱ�� ��ʱ һ������ȡ���ߵĲ���
                    PCMLen += iOnTime;
                    PCMLen += iOffTime;
                    break;
                default:
                    break;
            }
        }

        // ת����ADPCM ��ʽ
        //ADPCM_STATE state = new ADPCM_STATE();		
        List<ADPCM_STATE> state = new ArrayList<ADPCM_STATE>();
        state.add(new ADPCM_STATE());

        //д���Ӧ��Block����,����ÿ��BlockΪ0x100Byte��,0x1F9��Samples
        state.get(0).index = 0;
        for (int i = 0, j = 0; i < PCMLen; i += 505, j += 256) {
            //�趨��Ӧ�����ݽṹֵ						
            state.get(0).valprev = pPCM[i];
            //�趨Block��ͷ��ƫ����
            UsbHelper.channelList.get(iChannel).m_pPlayBuffer[j + 0] = (byte) (pPCM[i] & 0xFF);
            UsbHelper.channelList.get(iChannel).m_pPlayBuffer[j + 1] = (byte) (pPCM[i] >> 8);
            UsbHelper.channelList.get(iChannel).m_pPlayBuffer[j + 2] = (byte) (state.get(0).index);
            UsbHelper.channelList.get(iChannel).m_pPlayBuffer[j + 3] = 0;

            //ת������Ӧ��ADPCM����
            PCM2ADPCM(UsbHelper.channelList.get(iChannel).m_pPlayBuffer, j + 4, pPCM, i + 1, 504, state);
        }

        if (null != pPCM) {
            // �ͷ�PCM buffer
            pPCM = null;
        }

        // ׼���ò���buffer ,��������ͷ�

        UsbHelper.channelList.get(iChannel).m_PlayBufferLength = PCMLen / 2;    // ת��������ݰ���PCM 1/2
        UsbHelper.channelList.get(iChannel).m_PlayCurPosition = 0;            // ��ǰ����λ��
        UsbHelper.channelList.get(iChannel).m_PlayIndex = 0;            // ��ǰ���ŵ���Ƶ���ݰ����

    }

    /*
     * Sleep in milliseconds
     */
    private void Sleep(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    //For debug
    public void l(Object msg) {
        FileLog.v(TAG, ">==< " + msg.toString() + " >==<");
    }

    public void e(Object msg) {
        FileLog.v(TAG, ">==< " + msg.toString() + " >==<");
    }
}
