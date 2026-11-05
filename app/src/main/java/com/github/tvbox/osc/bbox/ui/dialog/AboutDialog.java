package com.github.tvbox.osc.bbox.ui.dialog;

import android.content.Context;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.bbox.R;

public class AboutDialog extends BaseDialog {

    public AboutDialog(@NonNull Context context) {
        super(context);
        setContentView(R.layout.dialog_about);
        TextView tvVersion = findViewById(R.id.tvVersion);
        if (tvVersion != null) {
            String v = "unknown";
            try { v = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; } catch (Exception ignore) {}
            tvVersion.setText("星辰影音 v" + v);
        }
    }
}
