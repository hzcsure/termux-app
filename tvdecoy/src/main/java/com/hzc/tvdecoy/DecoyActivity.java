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
 * 焦点开关（2026-09-16 在 MiTV_ASTP0 / Android 9 上实测过两个值）：
 *
 *   - true：去掉 NOT_FOCUSABLE → 诱饵持有真实焦点窗口，息屏必被杀，不会被 ANR 拖死。
 *     代价：诱饵期间遥控器按键先到本 Activity，TVHome 收不到导航键（可按 HOME 退出）。
 *
 *   - false（2026-09-16 起为默认值）：NOT_FOCUSABLE → 诱饵不抢焦点。
 *     ⚠️ 仅在【同 task】前提下才成立，否则必 ANR：
 *        · 同 task（当前设计，由 TVHome startActivity 不带 NEW_TASK 拉起）
 *          → TVHome 仅 pause，窗口与 surface 保留，焦点穿透给 TVHome，
 *            挡死 + 遥控兼得。
 *        · 独立 task（旧设计，singleInstance / 独立 taskAffinity / am start）
 *          → TVHome 被 stop（mDrawState=NO_SURFACE）拿不到焦点，
 *            mCurrentFocus=null → 任意按键触发 5s InputDispatcher 超时 → ANR →
 *            “Killing …: user request after error”，诱饵自杀，保护失效。
 *
 * 运行时仍可覆盖，省一次 CI 周期：
 *   adb shell am start -n com.hzc.tvdecoy/.DecoyActivity --ez focusable true
 * 若同 task 实测焦点仍为 null（WMS 不把焦点给 paused 但可见的下方窗口），
 * 就用上面的命令临时切 true 回退到“抢焦点但能用”的形态。
 */
public class DecoyActivity extends Activity {

    private static final boolean FOCUSABLE = false;

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
