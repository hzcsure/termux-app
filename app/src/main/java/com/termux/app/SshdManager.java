package com.termux.app;

import android.content.Context;
import android.content.res.AssetManager;

import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages embedded OpenSSH daemon (sshd) for background shell access.
 *
 * On first run, extracts sshd binaries, libraries, and host keys from
 * assets/sshd/ to /data/data/com.termux/sshd/. The daemon listens on
 * port 2223 and auto-restarts if the process exits.
 *
 * Adapted from TVHome_new SshdManager, package path changed to com.termux.
 */
public class SshdManager {
    private static final String LOG_TAG = "SshdManager";

    private static final String SSHD_DIR = "/data/data/com.termux/sshd";
    private static final int PORT = 2223;
    private static final AtomicBoolean started = new AtomicBoolean(false);

    public static void start(final Context ctx) {
        if (!started.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    extractAssets(ctx.getAssets());
                    runSshd();
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Failed to start sshd: " + e.getMessage());
                }
            }
        }, "sshd-starter").start();
    }

    private static void extractAssets(AssetManager am) throws Exception {
        File dir = new File(SSHD_DIR);
        if (dir.exists() && new File(SSHD_DIR + "/bin/sshd").exists()) {
            Logger.logDebug(LOG_TAG, "sshd already extracted, skipping");
            return;
        }

        Logger.logDebug(LOG_TAG, "Extracting sshd assets...");

        String[][] fileList = {
            {"sshd/bin/sshd", "bin/sshd"},
            {"sshd/libexec/sshd-session", "libexec/sshd-session"},
            {"sshd/libexec/sftp-server", "libexec/sftp-server"},
            {"sshd/lib/libandroid-glob.so", "lib/libandroid-glob.so"},
            {"sshd/lib/libandroid-support.so", "lib/libandroid-support.so"},
            {"sshd/lib/libcom_err.so.3", "lib/libcom_err.so.3"},
            {"sshd/lib/libcrypto.so.3", "lib/libcrypto.so.3"},
            {"sshd/lib/libgssapi_krb5.so.2", "lib/libgssapi_krb5.so.2"},
            {"sshd/lib/libk5crypto.so.3", "lib/libk5crypto.so.3"},
            {"sshd/lib/libkrb5.so.3", "lib/libkrb5.so.3"},
            {"sshd/lib/libkrb5support.so.0", "lib/libkrb5support.so.0"},
            {"sshd/lib/libresolv_wrapper.so", "lib/libresolv_wrapper.so"},
            {"sshd/lib/libssl.so.3", "lib/libssl.so.3"},
            {"sshd/lib/libtermux-auth.so", "lib/libtermux-auth.so"},
            {"sshd/lib/libz.so.1", "lib/libz.so.1"},
            {"sshd/etc/ssh/sshd_config", "etc/ssh/sshd_config"},
            {"sshd/etc/ssh/moduli", "etc/ssh/moduli"},
            {"sshd/etc/ssh/ssh_host_ecdsa_key", "etc/ssh/ssh_host_ecdsa_key"},
            {"sshd/etc/ssh/ssh_host_ecdsa_key.pub", "etc/ssh/ssh_host_ecdsa_key.pub"},
            {"sshd/etc/ssh/ssh_host_ed25519_key", "etc/ssh/ssh_host_ed25519_key"},
            {"sshd/etc/ssh/ssh_host_ed25519_key.pub", "etc/ssh/ssh_host_ed25519_key.pub"},
            {"sshd/etc/ssh/ssh_host_rsa_key", "etc/ssh/ssh_host_rsa_key"},
            {"sshd/etc/ssh/ssh_host_rsa_key.pub", "etc/ssh/ssh_host_rsa_key.pub"},
        };

        for (String[] pair : fileList) {
            File dest = new File(SSHD_DIR, pair[1]);
            dest.getParentFile().mkdirs();
            InputStream in = am.open(pair[0]);
            OutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            if (pair[0].contains("/bin/") || pair[0].contains("/libexec/"))
                dest.setExecutable(true);
        }

        Logger.logDebug(LOG_TAG, "sshd assets extracted successfully");
    }

    private static void runSshd() throws Exception {
        String libPath = SSHD_DIR + "/lib";
        String sshdBin = SSHD_DIR + "/bin/sshd";
        String conf = SSHD_DIR + "/etc/ssh/sshd_config";

        File wrapper = new File(SSHD_DIR, "start_sshd.sh");
        PrintWriter pw = new PrintWriter(wrapper);
        pw.println("#!/system/bin/sh");
        pw.println("export LD_LIBRARY_PATH=" + libPath);
        pw.println("export HOME=" + SSHD_DIR);
        pw.println("export PATH=" + SSHD_DIR + "/bin:/system/bin");
        pw.println("exec " + sshdBin + " -E " + SSHD_DIR + "/sshd.log -f " + conf + " -p " + PORT + " -D");
        pw.close();
        wrapper.setExecutable(true);

        Logger.logDebug(LOG_TAG, "Starting sshd on port " + PORT + "...");
        Process p = Runtime.getRuntime().exec(
            new String[]{"/system/bin/sh", wrapper.getAbsolutePath()});

        // Auto-restart loop
        while (true) {
            try {
                p.waitFor();
                Logger.logWarn(LOG_TAG, "sshd process exited, restarting in 5s...");
            } catch (InterruptedException e) {
                Logger.logDebug(LOG_TAG, "sshd interrupted");
                break;
            }
            Thread.sleep(5000);
            p = Runtime.getRuntime().exec(
                new String[]{"/system/bin/sh", wrapper.getAbsolutePath()});
        }
    }
}
