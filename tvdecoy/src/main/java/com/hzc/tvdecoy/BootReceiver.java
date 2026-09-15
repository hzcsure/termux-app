package com.hzc.tvdecoy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机 / 解锁 / 亮屏 时把诱饵 Activity 重新拉到前台。
 * 这样即使息屏时被杀，下次亮屏也会自动复位，无需人工干预。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action)
                || Intent.ACTION_SCREEN_ON.equals(action)) {
            Intent i = new Intent(context, DecoyActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        }
    }
}
