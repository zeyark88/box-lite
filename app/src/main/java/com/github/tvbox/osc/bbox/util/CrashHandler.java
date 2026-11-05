package com.github.tvbox.osc.bbox.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import com.github.tvbox.osc.bbox.ui.activity.HomeActivity;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃自动恢复：闪退时保存日志到 files/crash/，1 秒后自动重启回首页（TV 无人值守）。
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Context ctx;

    public CrashHandler(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static void install(Context ctx) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(ctx));
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            File dir = new File(ctx.getExternalFilesDir(null), "crash");
            if (!dir.exists()) dir.mkdirs();
            String name = "crash_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
            PrintWriter w = new PrintWriter(new File(dir, name));
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            w.write("Thread: " + t.getName() + "\n");
            w.write(sw.toString());
            w.close();
        } catch (Exception ignore) { }
        try {
            Intent i = new Intent(ctx, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pi);
        } catch (Exception ignore) { }
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(1);
    }
}
