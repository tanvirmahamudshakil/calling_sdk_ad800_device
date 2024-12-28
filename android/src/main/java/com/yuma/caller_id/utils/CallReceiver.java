package com.yuma.caller_id.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.Date;

public class CallReceiver extends BroadcastReceiver {

    private static int lastState = TelephonyManager.CALL_STATE_IDLE;
    private static Date callStartTime;
    private static boolean isIncoming;
    private static String savedNumber;

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            TelephonyManager telephony = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
            telephony.listen(new PhoneStateListener(){
                @Override
                public void onCallStateChanged(int state, String incomingNumber) {
                    super.onCallStateChanged(state, incomingNumber);
                    onCallStateChangedCall(context,state,incomingNumber);
                }
            }, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (Exception e) {
            Log.e("Broadcast", "error===>" + e.toString());
        }
    }


    public void onCallStateChangedCall(Context context, int state, String number) {
        if(lastState == state){
            //No change, debounce extras
            return;
        }
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            Log.e("----------------", "onCallStateChangedCall: ringing" );

            isIncoming = true;
            callStartTime = new Date();
            savedNumber = number;
            Intent i = new Intent("PHONE_CALL");
            i.putExtra("Number",number);
            i.putExtra("state",1);
            context.sendBroadcast(i);

        }else if( state == TelephonyManager.CALL_STATE_IDLE){
            Log.e("----------------", "onCallStateChangedCall: ringing" );
            //Went to idle-  this is the end of a call.  What type depends on previous state(s)
            if(isIncoming){
                Intent i = new Intent("PHONE_CALL");
                i.putExtra("Number",number);
                i.putExtra("state",0);
                context.sendBroadcast(i);

            }

        }

        lastState = state;
    }
}