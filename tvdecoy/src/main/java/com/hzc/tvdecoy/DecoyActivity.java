package com.hzc.tvdecoy;

import android.app.Activity;
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
 * ⚠️ 焦点开关（决定能否真的当替死鬼，需在 TV 上实测）：
 *   - false（默认，优先保证 TVHome 可用）：NOT_FOCUSABLE + NOT_TOUCH_MODAL
 *     → TVHome 始终前台、按键/触摸全穿透。但部分 MIUI 版本按“栈顶 resumed Activity”
 *       判定前台，此时诱饵不是前台 → 息屏可能仍杀 TVHome。
 *   - true（优先保证被 kill）：去掉 NOT_FOCUSABLE → 诱饵成为真正前台 Activity，
 *     息屏必被杀；代价是诱饵期间遥控器按键会先到本 Activity（圆点极小，影响可忽略，
 *     但 TVHome 暂时收不到导航键，亮屏重启后恢复）。
 */
public class DecoyActivity extends Activity {

    private static final boolean FOCUSABLE = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decoy);

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        // 仅一个 14dp 小红点，固定在左上角，不占屏
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 24;
        lp.y = 24;
        // 圆点以外区域的触摸穿透给 TVHome
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (FOCUSABLE) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        getWindow().setAttributes(lp);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }
}
