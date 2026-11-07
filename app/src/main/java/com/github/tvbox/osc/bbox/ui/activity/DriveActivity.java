package com.github.tvbox.osc.bbox.ui.activity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.bbox.R;
import com.github.tvbox.osc.bbox.base.BaseActivity;
import com.github.tvbox.osc.bbox.util.alist.AlistClient;
import com.github.tvbox.osc.bbox.util.webdav.WebDavClient;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;

/** 文件 / 存储空间：本地目录、WebDAV、Alist 三种存储源浏览与播放。 */
public class DriveActivity extends BaseActivity {
    private static final String KEY_URL = "webdav_url";
    private static final String KEY_USER = "webdav_user";
    private static final String KEY_PASS = "webdav_pass";
    private static final String KEY_ALIST_HOST = "alist_host";
    private static final String KEY_ALIST_USER = "alist_user";
    private static final String KEY_ALIST_PASS = "alist_pass";

    private static final int SRC_LOCAL = 0;
    private static final int SRC_WEBDAV = 1;
    private static final int SRC_ALIST = 2;
    private static final int REQ_LOCAL_PERM = 100;

    private TvRecyclerView list;
    private TextView tvPath;
    private TextView tvTitle;
    private TextView tvCfg;
    private NavAdapter rAdapter;
    private int mode = SRC_WEBDAV;

    // 本地
    private Stack<File> localStack = new Stack<>();
    private List<File> localItems = new ArrayList<>();
    // WebDAV
    private Stack<String> wdStack = new Stack<>();
    private List<WebDavClient.DavItem> wdItems = new ArrayList<>();
    private String currentWebDavUrl = "";
    // Alist
    private Stack<String> alistStack = new Stack<>();
    private List<AlistClient.AlistItem> alistItems = new ArrayList<>();
    private String alistHost = "";
    private String alistPath = "/";

    @Override
    protected int getLayoutResID() { return R.layout.activity_drive; }

    @Override
    protected void init() {
        list = findViewById(R.id.list);
        tvPath = findViewById(R.id.tvPath);
        tvTitle = findViewById(R.id.tvTitle);
        tvCfg = findViewById(R.id.tvCfg);
        tvCfg.setText("切 换");
        tvCfg.setOnClickListener(v -> chooseSource());
        list.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        rAdapter = new NavAdapter();
        list.setAdapter(rAdapter);
        list.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override public void onItemPreSelected(TvRecyclerView tvRecyclerView, View view, int position) { }
            @Override public void onItemSelected(TvRecyclerView tvRecyclerView, View view, int position) { }
            @Override public void onItemClick(TvRecyclerView tvRecyclerView, View view, int position) { }
        });
        chooseSource();
    }

    private void chooseSource() {
        String[] names = {"本地存储", "WebDAV 网盘", "Alist"};
        new AlertDialog.Builder(this)
            .setTitle("选择存储类型")
            .setItems(names, (d, w) -> {
                if (w == 0) { mode = SRC_LOCAL; enterLocal(); }
                else if (w == 1) { mode = SRC_WEBDAV; enterWebDav(); }
                else { mode = SRC_ALIST; enterAlist(); }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ---------------- 本地存储 ----------------
    private File pickLocalRoot() {
        File root = Environment.getExternalStorageDirectory();
        if (root == null || root.listFiles() == null) {
            File alt = new File("/storage");
            File[] vols = alt.listFiles();
            if (vols != null && vols.length > 0) root = alt;
        }
        return root != null ? root : new File("/storage");
    }

    private void enterLocal() {
        tvTitle.setText("本地存储");
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LOCAL_PERM);
            toast("需要存储权限才能浏览本地文件");
            return;
        }
        File root = pickLocalRoot();
        localStack.clear();
        loadLocal(root);
    }

    private void loadLocal(File dir) {
        tvPath.setText(dir.getAbsolutePath());
        File[] files = dir.listFiles();
        if (files == null) {
            rAdapter.setNames(new ArrayList<>());
            toast("无法访问该目录，请检查存储权限");
            return;
        }
        Arrays.sort(files, (a, b) -> Boolean.compare(a.isDirectory(), b.isDirectory()) * -1);
        localItems.clear();
        for (File f : files) localItems.add(f);
        List<String> names = new ArrayList<>();
        for (File f : localItems) names.add((f.isDirectory() ? "[D] " : "[F] ") + f.getName());
        rAdapter.setNames(names);
        if (localItems.isEmpty()) toast("目录为空");
    }

    private void clickLocal(int position) {
        File f = localItems.get(position);
        if (f.isDirectory()) {
            localStack.push(f.getParentFile());
            loadLocal(f);
        } else if (isVideo(f.getName())) {
            play(Uri.parse("file://" + f.getAbsolutePath()).toString());
        } else {
            toast("非视频文件：" + f.getName());
        }
    }

    // ---------------- WebDAV ----------------
    private void enterWebDav() {
        tvTitle.setText("WebDAV");
        String url = Hawk.get(KEY_URL, "");
        if (TextUtils.isEmpty(url)) showWebDavConfig();
        else { wdStack.clear(); loadWebDav(url); }
    }

    private void loadWebDav(String url) {
        currentWebDavUrl = url;
        tvPath.setText("加载中…");
        WebDavClient.list(url, Hawk.get(KEY_USER, ""), Hawk.get(KEY_PASS, ""), new WebDavClient.ListCallback() {
            @Override public void onResult(List<WebDavClient.DavItem> result) {
                wdItems.clear();
                wdItems.addAll(result);
                List<String> names = new ArrayList<>();
                for (WebDavClient.DavItem it : wdItems) names.add(it.toString());
                rAdapter.setNames(names);
                tvPath.setText(pathLabel(url));
                if (wdItems.isEmpty()) toast("目录为空");
            }
            @Override public void onError(String msg) {
                tvPath.setText("");
                rAdapter.setNames(new ArrayList<>());
                toast("读取失败：" + msg + "\n请检查配置（点\"切 换\"重新设置）");
            }
        });
    }

    private void clickWebDav(int position) {
        WebDavClient.DavItem it = wdItems.get(position);
        if (it.isDir) {
            wdStack.push(currentWebDavUrl);
            loadWebDav(WebDavClient.join(currentWebDavUrl, it.href));
        } else if (isVideo(it.name)) {
            String fileUrl = WebDavClient.join(currentWebDavUrl, it.href);
            play(WebDavClient.playUrl(fileUrl, Hawk.get(KEY_USER, ""), Hawk.get(KEY_PASS, "")));
        } else {
            toast("非视频文件：" + it.name);
        }
    }

    private void showWebDavConfig() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText etUrl = new EditText(this);
        etUrl.setHint("WebDAV 地址，如 https://dav.example.com/dav");
        etUrl.setText(Hawk.get(KEY_URL, ""));
        EditText etUser = new EditText(this);
        etUser.setHint("用户名（可空）");
        etUser.setText(Hawk.get(KEY_USER, ""));
        EditText etPass = new EditText(this);
        etPass.setHint("密码（可空）");
        etPass.setText(Hawk.get(KEY_PASS, ""));
        box.addView(etUrl); box.addView(etUser); box.addView(etPass);
        new AlertDialog.Builder(this)
            .setTitle("WebDAV 配置")
            .setView(box)
            .setPositiveButton("保存并打开", (d, w) -> {
                String url = etUrl.getText().toString().trim();
                if (TextUtils.isEmpty(url)) { toast("地址不能为空"); return; }
                if (!url.endsWith("/")) url = url + "/";
                Hawk.put(KEY_URL, url);
                Hawk.put(KEY_USER, etUser.getText().toString().trim());
                Hawk.put(KEY_PASS, etPass.getText().toString());
                wdStack.clear();
                loadWebDav(url);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ---------------- Alist ----------------
    private void enterAlist() {
        tvTitle.setText("Alist");
        alistHost = Hawk.get(KEY_ALIST_HOST, "");
        if (TextUtils.isEmpty(alistHost)) showAlistConfig();
        else { alistStack.clear(); loadAlist("/"); }
    }

    private void loadAlist(String path) {
        alistPath = path;
        tvPath.setText(path);
        AlistClient.list(alistHost, path, Hawk.get(KEY_ALIST_USER, ""), Hawk.get(KEY_ALIST_PASS, ""),
            new AlistClient.ListCallback() {
                @Override public void onResult(List<AlistClient.AlistItem> result) {
                    alistItems.clear();
                    alistItems.addAll(result);
                    List<String> names = new ArrayList<>();
                    for (AlistClient.AlistItem it : alistItems) names.add(it.toString());
                    rAdapter.setNames(names);
                    if (alistItems.isEmpty()) toast("目录为空");
                }
                @Override public void onError(String msg) {
                    tvPath.setText("");
                    rAdapter.setNames(new ArrayList<>());
                    toast("Alist 读取失败：" + msg + "\n请检查地址/账号点\"切\"重新设置");
                }
            });
    }

    private void clickAlist(int position) {
        AlistClient.AlistItem it = alistItems.get(position);
        String child = joinPath(alistPath, it.name);
        if (it.isDir) {
            alistStack.push(alistPath);
            loadAlist(child);
        } else if (isVideo(it.name)) {
            play(AlistClient.playUrl(alistHost, child, it.sign));
        } else {
            toast("非视频文件：" + it.name);
        }
    }

    private void showAlistConfig() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText etHost = new EditText(this);
        etHost.setHint("Alist 地址，如 http://192.168.1.10:5244");
        etHost.setText(Hawk.get(KEY_ALIST_HOST, ""));
        EditText etUser = new EditText(this);
        etUser.setHint("用户名（公开源可空）");
        etUser.setText(Hawk.get(KEY_ALIST_USER, ""));
        EditText etPass = new EditText(this);
        etPass.setHint("密码（可空）");
        etPass.setText(Hawk.get(KEY_ALIST_PASS, ""));
        box.addView(etHost); box.addView(etUser); box.addView(etPass);
        new AlertDialog.Builder(this)
            .setTitle("Alist 配置")
            .setView(box)
            .setPositiveButton("保存并打开", (d, w) -> {
                String host = etHost.getText().toString().trim();
                if (TextUtils.isEmpty(host)) { toast("地址不能为空"); return; }
                if (host.endsWith("/")) host = host.substring(0, host.length() - 1);
                alistHost = host;
                Hawk.put(KEY_ALIST_HOST, host);
                Hawk.put(KEY_ALIST_USER, etUser.getText().toString().trim());
                Hawk.put(KEY_ALIST_PASS, etPass.getText().toString());
                alistStack.clear();
                loadAlist("/");
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private String joinPath(String base, String name) {
        if (base.endsWith("/")) return base + name;
        return base + "/" + name;
    }

    // ---------------- 通用 ----------------
    private void clickItem(int position) {
        if (mode == SRC_LOCAL) clickLocal(position);
        else if (mode == SRC_WEBDAV) clickWebDav(position);
        else clickAlist(position);
    }

    private void play(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), "video/*");
            startActivity(Intent.createChooser(intent, "选择播放器播放"));
        } catch (Exception e) {
            toast("无法调用播放器：" + e.getMessage());
        }
    }

    private boolean isVideo(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov")
                || n.endsWith(".m4v") || n.endsWith(".ts") || n.endsWith(".flv") || n.endsWith(".webm")
                || n.endsWith(".rmvb") || n.endsWith(".wmv") || n.endsWith(".mpg") || n.endsWith(".3gp");
    }

    private String pathLabel(String url) {
        try { java.net.URL u = new java.net.URL(url); String p = u.getPath(); return TextUtils.isEmpty(p) ? "/" : p; }
        catch (Exception e) { return url; }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCAL_PERM && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            File root = pickLocalRoot();
            localStack.clear();
            loadLocal(root);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mode == SRC_LOCAL && !localStack.isEmpty()) { loadLocal(localStack.pop()); return true; }
            if (mode == SRC_WEBDAV && !wdStack.isEmpty()) { loadWebDav(wdStack.pop()); return true; }
            if (mode == SRC_ALIST && !alistStack.isEmpty()) { loadAlist(alistStack.pop()); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    private class NavAdapter extends RecyclerView.Adapter<NavAdapter.VH> {
        private final List<String> names = new ArrayList<>();
        void setNames(List<String> n) { names.clear(); names.addAll(n); notifyDataSetChanged(); }
        @Override public NavAdapter.VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(DriveActivity.this).inflate(R.layout.item_drive, parent, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(NavAdapter.VH h, int position) {
            h.tv.setText(names.get(position));
            h.itemView.setOnClickListener(v -> clickItem(h.getAdapterPosition()));
        }
        @Override public int getItemCount() { return names.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tv;
            VH(View v) { super(v); tv = v.findViewById(R.id.tvName); }
        }
    }
}