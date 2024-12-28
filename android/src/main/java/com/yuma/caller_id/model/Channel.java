package com.yuma.caller_id.model;

import android.os.Message;

import com.yuma.caller_id.usbController.UsbHelper;

import com.yuma.caller_id.utils.Constants;
import com.yuma.caller_id.utils.FileLog;


public class Channel {

    public String LineStatus = "";                //Line Status
    public int LineVoltage = 0;                    //Line Voltage
    public String VoiceTrigger = "";
    public String CallerId = "";                //CallerId
    public String Dtmf = "";                    //Dtmf Number
    public String Dialed = "";

    public int m_Channel;

    // FSK CID
    public int m_FSKCIDDataSize;
    public int m_iCarry;
    public byte m_szFSKCIDData[] = new byte[128];
    public char m_FskNum[] = new char[33];        // FSK CallerID Array


    // DTMF
    public char m_DtmfNum[] = new char[33];
    public int m_DtmfSize;

    // Voltage (calculate voltage average)
    public int m_VoltageIndex;                // received voltage count
    public int m_VoltageValue;                // received voltage sum
    public int[] m_VoltageArray = new int[256];    //100ms received voltage array
    public int m_CheckVoltageTime;            //  time for counting 100ms


    // PowerOff Check
    public boolean m_bCheckPowerOff;
    public int m_CheckPowerOffTime;


    // Idle Check
    public boolean m_bCheckIdle;
    public int m_CheckIdleTime;

    // HookOff Check
    public boolean m_bCheckHookOff;
    public int m_CheckHookOffTime;

    public int m_DtmfDetectTime;
    public int m_CallDuration;

    // Ring Check
    public int m_RingOn1;
    public int m_RingOn2;
    public boolean m_bCheckRingOn;
    public int m_CheckRingOnTime;
    public int m_RingCnt;
    public int m_RingOffTime;
    public int m_RingDuration;


    // Status
    public int m_State;
    public boolean m_bInbound;

    // Record
    public byte m_LastAdPcmIndex;    //  last packet index 0,1,2,3,4
    public byte pRecBuff[];
    public int m_RecLength;


    // play audio
    public byte[] m_pPlayBuffer;
    public int m_PlayBufferLength;
    public int m_PlayCurPosition;    //  current play position, play length is divided by 51 51 51 51 52 (256 one block)
    public int m_PlayIndex;        // play index


    public Channel() {
        init();
    }

    public void init() {
        LineStatus = "";
        LineVoltage = 0;
        VoiceTrigger = "";
        CallerId = "";
        Dtmf = "";
        Dialed = "";

        m_Channel = 0;
        m_FSKCIDDataSize = 0;
        m_iCarry = 0;
        for (int i = 0; i < m_szFSKCIDData.length; i++) m_szFSKCIDData[i] = 0;
        for (int i = 0; i < m_FskNum.length; i++) m_FskNum[i] = 0;

        m_DtmfSize = 0;
        for (int i = 0; i < m_DtmfNum.length; i++) m_DtmfNum[i] = 0;

        m_VoltageIndex = 0;
        m_CheckVoltageTime = 0;
        m_VoltageValue = 0;
        for (int i = 0; i < 256; i++) {
            m_VoltageArray[i] = 0x00;
        }

        m_bCheckPowerOff = false;
        m_CheckPowerOffTime = 0;

        m_bCheckIdle = false;
        m_CheckIdleTime = 0;

        m_bCheckHookOff = false;
        m_CheckHookOffTime = 0;
        m_DtmfDetectTime = 0;
        m_CallDuration = 0;

        m_bCheckRingOn = false;
        m_CheckRingOnTime = 0;
        m_RingOn1 = 0;
        m_RingOn2 = 0;
        m_RingCnt = 0;
        m_RingOffTime = 0;
        m_RingDuration = 0;

        // �˿�״̬
        m_State = Constants.CHANNELSTATE_POWEROFF;
        m_bInbound = false;

        //Record
        m_LastAdPcmIndex = 0;
        pRecBuff = null;
        m_RecLength = 0;

        //Play
        m_pPlayBuffer = null;
        m_PlayBufferLength = 0;
        m_PlayCurPosition = 0;
        m_PlayIndex = 0;
    }

    public void FskData(byte[] pszData1, int iPos, int iLen) {
        try {
            if (Constants.CHANNELSTATE_IDLE != m_State
                    && Constants.CHANNELSTATE_RINGON != m_State
                    && Constants.CHANNELSTATE_RINGOFF != m_State) {
                // When status is idle , ringon or ringoff, fsk is received.
                return;
            }

            byte[] pszData = new byte[iLen];
            System.arraycopy(pszData1, iPos, pszData, 0, iLen);

            if (m_FSKCIDDataSize < 1) {
                for (int i = 0; i < iLen; i++) {
                    if (0x55 == pszData[i]) {
                        m_iCarry++;
                        continue;
                    }
                    // It need to receive at least 8 0x55
                    if (m_iCarry < 8) {
                        m_iCarry = 0;
                        m_FSKCIDDataSize = 0;
                        for (int j = 0; j < m_szFSKCIDData.length; j++) m_szFSKCIDData[j] = 0;
                        for (int j = 0; j < m_FskNum.length; j++) m_FskNum[j] = 0;
                        continue;
                    }
                    if (pszData[i] != (byte) 0x80 && pszData[i] != (byte) 0x82 && pszData[i] != (byte) 0x04 && pszData[i] != (byte) 0x06) {
                        m_iCarry = 0;
                        m_FSKCIDDataSize = 0;
                        for (int j = 0; j < m_szFSKCIDData.length; j++) m_szFSKCIDData[j] = 0;
                        for (int j = 0; j < m_FskNum.length; j++) m_FskNum[j] = 0;
                        continue;
                    }
                    if (m_FSKCIDDataSize + (iLen - i) < m_szFSKCIDData.length) {
                        System.arraycopy(pszData, i, m_szFSKCIDData, m_FSKCIDDataSize, iLen - i);
                        m_FSKCIDDataSize += (iLen - i);
                        break;
                    }
                }
            } else {
                if (m_FSKCIDDataSize + iLen < m_szFSKCIDData.length) {
                    System.arraycopy(pszData, 0, m_szFSKCIDData, m_FSKCIDDataSize, iLen);
                    m_FSKCIDDataSize += iLen;
                } else {
                    m_iCarry = 0;
                    m_FSKCIDDataSize = 0;
                    for (int j = 0; j < m_szFSKCIDData.length; j++) m_szFSKCIDData[j] = 0;
                    for (int j = 0; j < m_FskNum.length; j++) m_FskNum[j] = 0;
                }
            }

            if (m_FSKCIDDataSize > 2 && m_szFSKCIDData[1] + 2 <= m_FSKCIDDataSize) // FSK frame length checking
            {
                AnalyseFSKCID(m_szFSKCIDData, m_FSKCIDDataSize);


                m_iCarry = 0;
                m_FSKCIDDataSize = 0;
                for (int j = 0; j < m_szFSKCIDData.length; j++) m_szFSKCIDData[j] = 0;
            }
        } catch (Exception e) {
            FileLog.v("Fsk", "Exception:" + e.getLocalizedMessage());
        }
    }


    //Analyze FSK data
    void AnalyseFSKCID(byte[] pszData, int iLen) {
        try {
            String s = "";
            for (int i = 0; i < iLen; i++) {
                s += String.format("%x,", pszData[i]);
            }
            FileLog.v("FskCIDData", s);
        } catch (Exception e) {

        }

        // Fsk header checking
        if (pszData[0] != (byte) 0x80
                && pszData[0] != (byte) 0x82
                && pszData[0] != 0x04
                && pszData[0] != 0x06
                && pszData[0] != (byte) 0x40) // 0x40 is dial fsk header
        {
            return;
        }

        // again checking number header position, 02,04,03

        int iHeader1Pos = 0; // 02
        int iHeader2Pos = 0; // 04
        int iHeader3Pos = 0; // 03
        int iNumHdrPos = 0;

        // �����һ��DDN��FSK�����ʽ����02,03���ֿ�ͷ��������, Ҫ�ĺ�����02
        // ���Բ��ҳ�02,03,04�⼸�ָ�ʽ��λ��, �����ж��Ƿ���02,04,03��������

        for (int i = 2; i < iLen; i++) {
            if (0x02 == pszData[i] && iHeader1Pos == 0) iHeader1Pos = i;
            if (0x04 == pszData[i] && iHeader2Pos == 0) iHeader2Pos = i;
            if (0x03 == pszData[i] && iHeader3Pos == 0) iHeader3Pos = i;
        }

        // ���жϿ�ͷ����02������
        if (iHeader1Pos != 0) {
            iNumHdrPos = iHeader1Pos;
        }

        // ���жϿ�ͷ����04������
        else if (iHeader2Pos != 0) {
            iNumHdrPos = iHeader2Pos;
        }

        // �����жϿ�ͷΪ03������
        else if (iHeader3Pos != 0) {
            iNumHdrPos = iHeader3Pos;
        } else {
            // ����fsk���Ƚ��ر𣬸�ʽֱ�Ӿ���ͷ+����+8λʱ��+����
        }

        int iNumLen = pszData[iNumHdrPos + 1];

        if (0x04 == pszData[iNumHdrPos]) // ��ʱ���FSK
        {
            iNumLen -= 8;    // ʱ�䳤��
            iNumHdrPos += 8;
        }

        if (iNumLen > 0) {
            if (iNumLen > 32) {
                iNumLen = 32;
            }

            for (int i = 0; i < iNumLen; i++) m_FskNum[i] = (char) pszData[iNumHdrPos + 2 + i];

            for (int i = 0; i < iNumLen; i++) {
                m_FskNum[i] &= 0x7F;
            }
            //FileLog.v("fsk", "get");
            String s = "";
            for (int i = 0; i < iNumLen; i++) {
                s += String.format("%c", m_FskNum[i]);
            }
            UsbHelper.channelList.get(m_Channel).CallerId = s;
//			FileLog.v("FskNum",UsbHelper.channelList.get(m_Channel).CallerId);

            if (Constants.CHANNELSTATE_IDLE != UsbHelper.channelList.get(m_Channel).m_State) {
                FileLog.v("Unknown", "Error");
            }

            if (UsbHelper.context != null) {
                Message msg = new Message();
                msg.what = Constants.AD800_LINE_CALLERID;
                msg.arg1 = m_Channel;
                UsbHelper.DeviceMsgHandler.sendMessage(msg);
            }
        }
    }

    public void DTMFData(byte[] pszData, int iPos, int iLen) {
        try {
            for (int i = 0; i < iLen; i++) {
                m_DtmfNum[m_DtmfSize] = DTMFToChar(pszData[i + iPos]);
                m_DtmfSize++;
                if (m_DtmfSize >= 32) {
                    m_DtmfSize = 0;
                    for (int j = 0; j < m_DtmfNum.length; j++) m_DtmfNum[j] = 0;
                }

                UsbHelper.channelList.get(m_Channel).Dtmf = "";
                for (int j = 0; j < m_DtmfSize; j++) {
                    UsbHelper.channelList.get(m_Channel).Dtmf += m_DtmfNum[j];//String.format("%c", m_DtmfNum[j]);
                }
                //DTMF Detected Timer reset
                if (UsbHelper.channelList.get(m_Channel).Dtmf != "" && UsbHelper.channelList.get(m_Channel).m_State == Constants.CHANNELSTATE_PICKUP)
                    m_DtmfDetectTime = 0;

                if (UsbHelper.context != null) {
                    Message msg = new Message();
                    msg.what = Constants.AD800_LINE_DTMF;
                    msg.arg1 = m_Channel;
                    UsbHelper.DeviceMsgHandler.sendMessage(msg);
                }
//				FileLog.v("dtmf", UsbHelper.channelList.get(m_Channel).Dtmf);
            }
        } catch (Exception e) {
            FileLog.v("Dtmf", "Exception:" + e.getLocalizedMessage());
        }
    }

    char DTMFToChar(byte szDTMF) {
        char szValue = 0;
        switch (szDTMF) {
            case 0x0A:
                szValue = '0';
                break;
            case 0x0B:
                szValue = '*';
                break;
            case 0x0C:
                szValue = '#';
                break;
            case 0x0D:
                szValue = 'A';
                break;
            case 0x0E:
                szValue = 'B';
                break;
            case 0x0F:
                szValue = 'C';
                break;
            case 0x00:
                szValue = 'D';
                break;
            default: {
                if (szDTMF >= 0 && szDTMF <= 9) {
                    szValue = (char) (szDTMF + '0');
                }
            }
            break;
        }

        return szValue;
    }

    public void GetVoltage(int Value) {
        if (m_VoltageIndex < 256) {
            m_VoltageArray[m_VoltageIndex] = (int) ((Value * (3.3 / 65535) - 1.650) * 60);
        }

        m_VoltageValue += Math.abs((Value * (3.3 / 65535) - 1.650) * 60);
        m_VoltageIndex++;
    }

    public void RecBuffer(byte[] AudioBuff, int iPos, int Length) {
        if (AudioBuff == null || Length < 51 || Length > 52) return;

        if (pRecBuff == null) {
            pRecBuff = new byte[Constants.MAXRECTIME + 1];
            m_RecLength = 0;
        }

        if (m_RecLength + Length <= Constants.MAXRECTIME) {
            System.arraycopy(AudioBuff, iPos, pRecBuff, m_RecLength, Length);
            m_RecLength += Length;
        }

    }


    public boolean PlayBuffer() {
        if (null == m_pPlayBuffer) {
            return false;
        }

        int PlayLength = 51;
        int CurIndex = m_PlayIndex;

        switch (m_PlayIndex) {
            case 0:
                m_PlayIndex = 1;
                PlayLength = 51;
                break;
            case 1:
                m_PlayIndex = 2;
                PlayLength = 51;
                break;
            case 2:
                m_PlayIndex = 3;
                PlayLength = 51;
                break;
            case 3:
                m_PlayIndex = 4;
                PlayLength = 51;
                break;
            case 4:
                m_PlayIndex = 0;
                PlayLength = 52;
                break;
            default:
                return false;
        }


        if (PlayLength + m_PlayCurPosition > m_PlayBufferLength) {
            // play finish
            return false;
        }

        //FileLog.v("UsbRunnable","play buffer "+m_PlayIndex+" "+PlayLength);
        byte[] WriteBuff = new byte[64];
        for (int i = 0; i < 64; i++) WriteBuff[i] = 0;

        WriteBuff[0] = 0x02;
        WriteBuff[1] = (byte) (7 + PlayLength);
        WriteBuff[2] = UsbHelper.sUsbController.m_DataPackIndex;
        WriteBuff[3] = (byte) (m_Channel & 0xFF);
        WriteBuff[4] = 0x00;
        WriteBuff[5] = 0x00;
        WriteBuff[6] = Constants.COMMAND_PLAY;
        WriteBuff[7] = (byte) ((PlayLength + 1) & 0xFF);            // current play packet length
        WriteBuff[8] = (byte) (CurIndex & 0xFF);            // music packet adpcm index


        System.arraycopy(m_pPlayBuffer, m_PlayCurPosition, WriteBuff, 9, PlayLength);            //music data

        UsbHelper.sUsbController.m_WriteBuffList.add(WriteBuff);

        UsbHelper.sUsbController.m_DataPackIndex++;
        m_PlayCurPosition += PlayLength;

        if (UsbHelper.sUsbController.m_DataPackIndex > 0xFF) {
            UsbHelper.sUsbController.m_DataPackIndex = 0x00;
        }
        return true;
    }
}
