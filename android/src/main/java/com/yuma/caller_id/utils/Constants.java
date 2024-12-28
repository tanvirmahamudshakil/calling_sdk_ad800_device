package com.yuma.caller_id.utils;

public class Constants {

	public final static int DEVICE_VENDOR_ID = 0xC5CB;			//Device Vendor Id
	public final static int DEVICE_PRODUCT_ID = 0x0002;			//Device Product Id
	
	public final static int HEADER_OUT = 0x02; // FOR ANDROID SEND DATA
	public final static int HEADER_IN = 0x03;  // FOR MCU SEND DATA
	public final static int EEPROMDATA_SIZE = 128;
	
	//EEPROM DATA ADDRESS
	public final static int DEVSN_ADDR = 1;					//Device SN - 4
	public final static int DEVTYPE_ADDR = 5;				//Device Type - 1
	public final static int PLAYVOL_ADDR = 6;				//Play Volume - 8
	public final static int RECVOL_ADDR = 14;				//Record Volume - 8
	public final static int VOICEPARAM_ADDR = 22;			//Voice Parameter - 32 = 4*8
	
	//DEVICE_COMMAND	
	public final static int COMMAND_CONTROL = 0x30; 		//command
	public final static int COMMAND_FSK = 0x31;				//fsk
	public final static int COMMAND_DTMF = 0x32;			//dtmp
	public final static int COMMADN_REC = 0x33;				//Record data - Index(0~4)+data(51/52bytes)
	public final static int COMMAND_PLAY = 0x34;			//Play data
	public final static int COMMAND_VOLTAGE = 0x35;			//Co-line voltage - Stable two bytes data(16 data),MSB First Format, 16 AD data

	
	//DEVICE_COMMAND_CONTROL
	public final static int DEVICE_CTRL_ACK = 0x00; 		//Response ACK command
	public final static int DEVICE_CTRL_REC = 0x01;			//Start record command
	public final static int DEVICE_CTRL_STOPREC = 0x02;		//Stop record command
	public final static int DEVICE_CTRL_VER = 0x03;		 	//Read Software Version
	public final static int DEVICE_CTRL_RECVOL = 0x04;		//Set Input-gain
	public final static int DEVICE_CTRL_PLAYVOL = 0x05;		//Set output-gain
	public final static int DEVICE_CTRL_PLAY = 0x06;		//Start play
	public final static int DEVICE_CTRL_STOPPLAY = 0x07;	//Stop play	
	public final static int DEVICE_CTRL_VOCACK = 0x08;		//Voice data request
	public final static int DEVICE_CTRL_DISPHONE = 0x09;	//Relay control
	public final static int DEVICE_CTRL_LINEBUSY = 0x0A;   	//Busy line control
	public final static int DEVICE_CTRL_AGC = 0x0B;			//AGC Control
	
	public final static int DEVICE_CTRL_WRITEEPROM = 0x10;	//Write EEPROM
	public final static int DEVICE_CTRL_READEPROM = 0x11;	//Read EEPROM

	public final static int DEVICE_CTRL_CHECK = 0xFF; 		//Device Check
	
	
	//Channel Status
	public final static int CHANNELSTATE_POWEROFF = 1;		//Disconnect
	public final static int CHANNELSTATE_IDLE  = 2;			//Idle
	public final static int CHANNELSTATE_PICKUP	= 3; 		//Dialing
	public final static int CHANNELSTATE_RINGON = 4;		//Ring On
	public final static int CHANNELSTATE_RINGOFF = 5;		//Ring Off
	public final static int CHANNELSTATE_ANSWER = 6;		//Answer(Incoming Call)
	public final static int CHANNELSTATE_OUTGOING = 7;		//Outgoing Call 
	public final static int CHANNELSTATE_UNKNOWN = 8;		//Unknown
	
	//	
//	public final static int STATE_EVENT	= 1;
//	public final static int DTMF_EVENT = 2;
//	public final static int FSK_EVENT = 3;
//	public final static int AUDIO_EVENT = 4;
//	public final static int VOLTAGE_EVENT = 5;
	
	//Event Status
	public final static int AD800_DEVICE_CONNECTION = 0;	// (Device connection status)			 
	public final static int AD800_LINE_STATUS = 1; // (Line Status e.g pickup,hangup,ringing,power off)	
	public final static int AD800_LINE_VOLTAGE = 2; // (Line voltage)
	public final static int AD800_LINE_POLARITY = 3; // (Line Polarity Changed)
	public final static int AD800_LINE_CALLERID = 4; // (Caller Id number)
	public final static int AD800_LINE_DTMF = 5; // (Dialed number)
	public final static int AD800_REC_DATA = 6;	// (Recording data)
	public final static int AD800_PLAY_FINISHED = 7; // (Playback finished)
	public final static int AD800_VOICETRIGGER = 8; // (Voice trigger status)
	public final static int AD800_BUSYTONE = 9;	// (Busy tone status)
	public final static int AD800_DTMF_FINISHED = 10; // (Send DTMF finished)
	
	
	public final static int MAXRECTIME			= 7372800;		// 30 ���� 30*60*4096
	public final static int LEN_BLOCK			= 0x100;			// ADPCM��ÿ��Block����
	public final static int LEN_SMPBLK			= 0x1F9;			// ADPCMÿ��Block�Ĳ�����
	
	public final static int indexTable[] = new int[] 
	{
			-1, -1, -1, -1, 2, 4, 6, 8,
			-1, -1, -1, -1, 2, 4, 6, 8,
	};

	public final static int stepsizeTable[] = new int[] 
	{
		7,     8,     9,    10,    11,    12,    13,    14,    16,    17,
		19,    21,    23,    25,    28,    31,    34,    37,    41,    45,
		50,    55,    60,    66,    73,    80,    88,    97,   107,   118,
		130,   143,   157,   173,   190,   209,   230,   253,   279,   307,
		337,   371,   408,   449,   494,   544,   598,   658,   724,   796,
		876,   963,  1060,  1166,  1282,  1411,  1552,  1707,  1878,  2066,
		2272,  2499,  2749,  3024,  3327,  3660,  4026,  4428,  4871,  5358,
		5894,  6484,  7132,  7845,  8630,  9493, 10442, 11487, 12635, 13899,
		15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
	};
	
}
