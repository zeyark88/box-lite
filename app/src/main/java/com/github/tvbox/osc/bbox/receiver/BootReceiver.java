package com.github.tvbox.osc.bbox.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.github.tvbox.osc.bbox.ui.activity.HomeActivity;


public class BootReceiver extends BroadcastReceiver {
@Override
public void onReceive(Context context, Intent intent) {

    String action = intent.getAction();

    if (Intent.ACTION_BOOT_COMPLETED.equals(action)
        || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

        Intent launch = new Intent(context, HomeActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);

    }
}
}
