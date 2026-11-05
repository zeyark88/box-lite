package com.github.tvbox.osc.bbox.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.github.tvbox.osc.bbox.R;
import com.github.tvbox.osc.bbox.base.BaseActivity;
import com.github.tvbox.osc.bbox.util.webdav.WebDavClient;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/** 网盘（WebDAV）浏览页。 */
public class DriveActivity extends BaseActivity {
    private static final String KEY_URL = "webdav_url";
    private static final String KEY_USER = "webdav_user";
    private static final String KEY_PASS = "webdav_pass";

    private ListView list;
    private TextView tvPath;
    private ArrayAdapter<String> adapter;
    private List<WebDavClient.DavItem> items = new ArrayList<>();
    private Stack<String> backStack = new Stack<>();
    private String currentUrl;

    @Override
    protected int getLayoutResID() { return R.layout.activity_drive; }

    @Override
    protected void init() {
        list = findViewById(R.id.list);
        tvPath = findViewById(R.id.tvPath);
        findViewById(R.id.tvCfg).setOnClickListener(v -> showConfig());
        adapter = new ArrayAdapter<>(this, R.layout.item_drive, R.id.tvName, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> clickItem(position));
        String url = Hawk.get(KEY_URL, "");
        if (TextUtils.isEmpty(url)) showConfig();
        else loadDir(url);
    }

    private void loadDir(String url) {
        currentUrl = url;
        tvPath.setText("加载中…");
        String user = Hawk.get(KEY_USER, "");
        String pass = Hawk.get(KEY_PASS, "");
        WebDavClient.list(url, user, pass, new WebDavClient.ListCallback() {
            @Override public void onResult(List<WebDavClient.DavItem> result) {
                items.clear();
                items.addAll(result);
                List<String> names = new ArrayList<>();
                for (WebDavClient.DavItem it : items) names.add(it.toString());
                adapter.clear();
                adapter.addAll(names);
                adapter.notifyDataSetChanged();
                tvPath.setText(pathLabel(url));
                if (items.isEmpty()) toast("目录为空");
            }
            @Override public void onError(String msg) {
                tvPath.setText("");
                toast("读取失败：" + msg + "\n请检查配置（点右上角\"配 置\"）");
            }
        });
    }

    private void clickItem(int position) {
        WebDavClient.DavItem it = items.get(position);
        if (it.isDir) {
            backStack.push(currentUrl);
            loadDir(WebDavClient.join(currentUrl, it.href));
        } else if (isVideo(it.name)) {
            String fileUrl = WebDavClient.join(currentUrl, it.href);
            String play = WebDavClient.playUrl(fileUrl, Hawk.get(KEY_USER, ""), Hawk.get(KEY_PASS, ""));
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.parse(play), "video/*");
                startActivity(Intent.createChooser(intent, "选择播放器播放"));
            } catch (Exception e) {
                toast("无法调用播放器：" + e.getMessage());
            }
        } else {
            toast("非视频文件：" + it.name);
        }
    }

    private boolean isVideo(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov")
                || n.endsWith(".m4v") || n.endsWith(".ts") || n.endsWith(".flv") || n.endsWith(".webm")
                || n.endsWith(".rmvb") || n.endsWith(".wmv") || n.endsWith(".mpg") || n.endsWith(".3gp");
    }

    private void showConfig() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText etUrl = new EditText(this);
        etUrl.setHint("WebDAV 地址，如 https://dav.example.com/dav 或 http://192.168.1.10:5244/dav");
        etUrl.setText(Hawk.get(KEY_URL, ""));
        final EditText etUser = new EditText(this);
        etUser.setHint("用户名（可空）");
        etUser.setText(Hawk.get(KEY_USER, ""));
        final EditText etPass = new EditText(this);
        etPass.setHint("密码（可空）");
        etPass.setText(Hawk.get(KEY_PASS, ""));
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(etUrl);
        box.addView(etUser);
        box.addView(etPass);
        new AlertDialog.Builder(this)
            .setTitle("网盘配置（WebDAV）")
            .setView(box)
            .setPositiveButton("保存并打开", (d, w) -> {
                String url = etUrl.getText().toString().trim();
                if (TextUtils.isEmpty(url)) { toast("地址不能为空"); return; }
                if (!url.endsWith("/")) url = url + "/";
                Hawk.put(KEY_URL, url);
                Hawk.put(KEY_USER, etUser.getText().toString().trim());
                Hawk.put(KEY_PASS, etPass.getText().toString());
                backStack.clear();
                loadDir(url);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private String pathLabel(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String p = u.getPath();
            return TextUtils.isEmpty(p) ? "/" : p;
        } catch (Exception e) { return url; }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && !backStack.isEmpty()) {
            loadDir(backStack.pop());
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
