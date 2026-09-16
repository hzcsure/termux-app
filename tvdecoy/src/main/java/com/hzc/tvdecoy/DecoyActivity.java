package com.hzc.tvdecoy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * 透明“前台诱饵”Activity。
 *
 * 目的：永远视觉上压在 TVHome 之上，但完全不抢 TVHome 的输入；
 * 当电视息屏、小米电源服务强杀“前台 app 的整个 cgroup”时，
 * 被杀的是本 app 的独立 cgroup，TVHome（及 sshd/crond/sing-box）得以幸存。
 *
 * 焦点开关（2026-09-16 已在 MiTV_ASTP0 / Android 9 上实测，两个值都跑过）：
 *
 *   - true（当前值，必须）：去掉 NOT_FOCUSABLE → 诱饵持有真实焦点窗口，
 *     息屏必被杀，且不会被 ANR 拖死。
 *
 *   - false（已实测否决，勿再改回）：NOT_FOCUSABLE 虽然也能让诱饵成为
 *     mResumedActivity / mFocusedApp（挡死逻辑本身成立），但窗口 flags 导致
 *     mCurrentFocus=null —— 系统里“没有任何窗口持有焦点”。后果有两个：
 *       1) 任意遥控器按键都会触发 InputDispatcher 等待 5s 超时 → ANR →
 *          “Killing …: user request after error”，诱饵自己先死，
 *          之后 TVHome 重回前台，下一次息屏照杀 TVHome，保护失效；
 *       2) 诱饵在位期间 TVHome 完全收不到按键。
 *     false 下挡死只在“全程无任何按键”时才侥幸生效，不可用于实际部署。
 *
 * 注意：true 的代价是诱饵期间遥控器按键先到本 Activity（圆点仅 28x28，
 * 不遮挡 TVHome 画面）。若要同时保住 TVHome 导航，需再加 dispatchKeyEvent
 * 分流（POWER/SLEEP 留前台，其余键 finish() 还焦点）+ 空闲自动重拉。
 */
public class DecoyActivity extends Activity {

    private static final boolean FOCUSABLE = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decoy);

        // 运行时可切换焦点，省一次 CI 周期：
        //   adb shell am start -n com.hzc.tvdecoy/.DecoyActivity --ez focusable false
        boolean focusable = FOCUSABLE;
        Intent it = getIntent();
        if (it != null && it.hasExtra("focusable")) {
            focusable = it.getBooleanExtra("focusable", FOCUSABLE);
        }

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        // 仅一个 14dp 小红点，固定在左上角，不占屏
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 24;
        lp.y = 24;
        // 圆点以外区域的触摸穿透给 TVHome
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (focusable) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        getWindow().setAttributes(lp);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }
}
