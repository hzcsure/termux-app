package com.hzc.tvdecoy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 悬浮窗诱饵（方案 B）。
 *
 * 与 DecoyActivity 的区别：本 Service 不启动任何 Activity，只在屏幕上挂一个
 * TYPE_APPLICATION_OVERLAY 的极小窗口（默认 1x1，透明）。
 *
 * 关键意图：验证「小米 PowerManagerService 的 gotoSleepStayAliveProcess
 * 取到的 current 前台应用」到底按什么判定——
 *   - 若按 Activity 栈顶 ⇒ 悬浮窗不算前台，挡不到 kill（方案 B 否决）
 *   - 若按可见/adj ⇒ 悬浮窗可能就能顶包，且因为窗口是 NOT_FOCUSABLE +
 *     NOT_TOUCHABLE，TVHome 的输入完全不受影响（比 Activity 方案更干净）
 *
 * 用法：
 *   adb shell appops set com.hzc.tvdecoy SYSTEM_ALERT_WINDOW allow
 *   adb shell am startservice -n com.hzc.tvdecoy/.DecoyService
 *   adb shell am startservice -n com.hzc.tvdecoy/.DecoyService --ez stop true
 */
public class DecoyService extends Service {

    private static final String CHANNEL_ID = "decoy_overlay";
    private static final int NOTIF_ID = 1;

    private WindowManager mWm;
    private View mOverlay;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("stop", false)) {
            removeOverlay();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (mOverlay == null) {
            addOverlay();
        }
        return START_STICKY;
    }

    private void addOverlay() {
        mWm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (mWm == null) return;

        mOverlay = new View(this);
        mOverlay.setBackgroundColor(Color.TRANSPARENT);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;

        try {
            mWm.addView(mOverlay, lp);
        } catch (Exception e) {
            mOverlay = null;
        }
    }

    private void removeOverlay() {
        if (mOverlay != null && mWm != null) {
            try {
                mWm.removeView(mOverlay);
            } catch (Exception ignored) {
            }
        }
        mOverlay = null;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "decoy", NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("tvdecoy")
                .setContentText("overlay decoy running")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .build();
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
