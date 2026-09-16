package com.hzc.tvdecoy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * 透明“前台诱饵”Activity。
 *
 * 目的：永远占据「栈顶 resumed Activity」这个位置，替 TVHome 承受小米电视
 * 息屏时的 forceStopPackage，同时让用户感觉遥控器照常操作 TVHome。
 *
 * 背景（已逆向 + 实测）：小米 PowerManagerService 息屏时会
 *   gotoSleepStayAliveProcess current:&lt;pkg&gt; not in whitelist
 *   → gotoHomeLauncher → forceStopPackage package:&lt;pkg&gt;
 * 白名单硬编码在 framework（services.vdex），无 root 不可改；被杀的判据是
 * 【栈顶 resumed Activity 所属包】，与可见窗口 / overlay / adj 均无关
 * （TYPE_APPLICATION_OVERLAY 悬浮窗已实测无法挡 kill）。
 *
 * 焦点策略（2026-09-16 在 MiTV_ASTP0 / Android 9 上实测）：
 *
 *   必须 FOCUSABLE=true（去掉 NOT_FOCUSABLE），否则必 ANR 自杀：
 *     NOT_FOCUSABLE 时诱饵自身没有可聚焦窗口，但系统仍视其为 mFocusedApp，
 *     DisplayContent 停在 FocusedWindow=&lt;null&gt;，**不会**回退到其它 App 的
 *     窗口 —— 即使 TVHome 窗口当时可见（mViewVisibility=0x0 mObscured=false）
 *     也一样。随后任意按键触发 InputDispatcher 5s 超时 →
 *     “ANR in com.hzc.tvdecoy” → 诱饵自己先死，TVHome 重回前台，
 *     下一次息屏照杀 TVHome，保护失效。
 *
 *   “把诱饵塞进 TVHome 同一个 task 让焦点穿透”这条备选路径也已实测否决：
 *     从 TVHome startActivity（不带 NEW_TASK）后，诱饵仍然每次另起 task
 *     （termux t73 vs decoy t74/t75/t77），home stack 不接受外来 Activity；
 *     且如上所述，即便窗口可见焦点也不会穿透。
 *
 * 结论：焦点不可能让出去。改为【应用层按键转发】—— 诱饵保持可聚焦拿到焦点，
 * 收到遥控器按键后把语义丢一条广播给 TVHome，由 TVHome 自己执行导航。
 * 用户感知就是“遥控器仍然能用”。
 */
public class DecoyActivity extends Activity {

    private static final String TAG = "DecoyActivity";

    /** 运行时可覆盖，用于现场 A/B：--ez focusable false */
    private static final boolean FOCUSABLE = true;

    /** 按键转发目标包（TVHome）。两条 APK 必须各自独立安装，包名不同。 */
    private static final String TARGET_PKG = "com.termux";
    /** 与 TVHome 侧 LauncherActivity 的 DECOY_RELAY_ACTION 保持一致。 */
    private static final String RELAY_ACTION = "com.hzc.tvdecoy.KEY";
    /** 与 TVHome 的 LONG_PRESS(500ms) 保持一致，用于区分“长按开底部栏”。 */
    private static final long LONG_PRESS_MS = 500L;

    private long mCenterDownTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decoy);

        boolean focusable = FOCUSABLE;
        Intent it = getIntent();
        if (it != null && it.hasExtra("focusable")) {
            focusable = it.getBooleanExtra("focusable", FOCUSABLE);
        }

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        // 左上角日期时间标签（见 res/layout/activity_decoy.xml），约 150x35dp，
        // 落在 TVHome 顶部 114dp 空白区内，不压图标
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 24;
        lp.y = 24;
        // 整个窗口不接收触摸：标签变大后若只 NOT_TOUCH_MODAL，被它盖住的那一块
        // 会吃掉触摸事件。电视用遥控操作，本窗口只需要【按键焦点】，
        // NOT_TOUCHABLE 不影响焦点与按键分发（那由 FLAG_NOT_FOCUSABLE 管）。
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (focusable) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        getWindow().setAttributes(lp);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int kc = event.getKeyCode();
        int action = event.getAction();

        switch (kc) {
            // 方向键：DOWN 立即转发，保证光标移动跟手（遥控器连发也只认 DOWN）
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (action == KeyEvent.ACTION_DOWN) relay(kc, false);
                return true;

            // 确认键：DOWN 记时，UP 时按按住时长决定“短按=启动 / 长按=底部栏”
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (action == KeyEvent.ACTION_DOWN) {
                    // 只在首次 DOWN 记时；否则遥控器长按的重复事件会把计时清零，
                    // 长按永远判定不出来
                    if (event.getRepeatCount() == 0) mCenterDownTime = event.getDownTime();
                    return true;
                }
                if (action == KeyEvent.ACTION_UP) {
                    relay(kc, event.getEventTime() - mCenterDownTime >= LONG_PRESS_MS);
                    return true;
                }
                return true;

            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                if (action == KeyEvent.ACTION_UP) relay(kc, false);
                return true;

            default:
                // 音量等系统键交回系统，不要吞掉
                return super.dispatchKeyEvent(event);
        }
    }

    /** 把一次按键语义发给 TVHome，由它操作自己的桌面网格。 */
    private void relay(int keyCode, boolean isLong) {
        try {
            Intent i = new Intent(RELAY_ACTION);
            i.setPackage(TARGET_PKG);
            i.putExtra("key", keyCode);
            i.putExtra("long", isLong);
            sendBroadcast(i);
        } catch (Exception e) {
            Log.w(TAG, "relay failed: " + e.getMessage());
        }
    }
}
