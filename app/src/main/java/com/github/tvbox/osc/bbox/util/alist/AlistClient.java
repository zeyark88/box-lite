package com.github.tvbox.osc.bbox.util.alist;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** 极简 Alist 客户端：/api/fs/list 列目录，/d/<path> 播放直链。 */
public class AlistClient {
    private static final String TAG = "AlistClient";

    public static class AlistItem {
        public String name;
        public boolean isDir;
        public long size;
        public String sign;
        @Override public String toString() { return (isDir ? "[D] " : "[F] ") + name; }
    }

    public interface ListCallback { void onResult(List<AlistItem> items); void onError(String msg); }

    /**
     * 列出目录。host 形如 http://192.168.1.10:5244（不带末尾/），path 形如 /。
     * user/pass 可空（公开 Alist）；若提供则先通过 /api/auth/login 拿 token。
     */
    public static void list(final String host, final String path, final String user, final String pass, final ListCallback cb) {
        new Thread(() -> {
            try {
                final String token = obtainToken(host, user, pass);
                HttpURLConnection c = (HttpURLConnection) new URL(host + "/api/fs/list").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                c.setRequestProperty("Content-Type", "application/json");
                if (token != null) c.setRequestProperty("Authorization", token);
                JsonObject body = new JsonObject();
                body.addProperty("path", path);
                body.addProperty("password", "");
                body.addProperty("page", 1);
                body.addProperty("per_page", 0);
                body.addProperty("refresh", false);
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.flush();
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) {
                    c.disconnect();
                    post(() -> cb.onError("HTTP " + code));
                    return;
                }
                String s = readAll(c.getInputStream());
                c.disconnect();
                JsonObject root = JsonParser.parseString(s).getAsJsonObject();
                if (!root.has("code") || root.get("code").getAsInt() != 200) {
                    final String msg = root.has("message") ? root.get("message").getAsString() : "Alist 返回错误";
                    post(() -> cb.onError(msg));
                    return;
                }
                final List<AlistItem> items = new ArrayList<>();
                JsonObject data = root.has("data") ? root.getAsJsonObject("data") : null;
                if (data != null && data.has("content") && data.get("content").isJsonArray()) {
                    JsonArray arr = data.getAsJsonArray("content");
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject o = arr.get(i).getAsJsonObject();
                        AlistItem it = new AlistItem();
                        it.name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : "";
                        it.isDir = o.has("is_dir") && !o.get("is_dir").isJsonNull() && o.get("is_dir").getAsBoolean();
                        it.size = o.has("size") && !o.get("size").isJsonNull() ? o.get("size").getAsLong() : 0;
                        it.sign = o.has("sign") && !o.get("sign").isJsonNull() ? o.get("sign").getAsString() : "";
                        if (!it.name.isEmpty()) items.add(it);
                    }
                }
                post(() -> cb.onResult(items));
            } catch (Exception e) {
                Log.e(TAG, "list err", e);
                post(() -> cb.onError(e.getMessage()));
            }
        }).start();
    }

    /** 播放直链：/d/<path>?sign=xxx */
    public static String playUrl(String host, String path, String sign) {
        try {
            String enc = encodePath(path);
            return host + "/d" + enc + (sign == null || sign.isEmpty() ? "" : "?sign=" + sign);
        } catch (Exception e) { return host + "/d" + path; }
    }

    private static String obtainToken(String host, String user, String pass) {
        if (user == null || user.isEmpty()) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(host + "/api/auth/login").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestProperty("Content-Type", "application/json");
            JsonObject b = new JsonObject();
            b.addProperty("username", user);
            b.addProperty("password", pass);
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            os.write(b.toString().getBytes("UTF-8"));
            os.flush();
            String s = readAll(c.getInputStream());
            c.disconnect();
            JsonObject root = JsonParser.parseString(s).getAsJsonObject();
            if (root.has("data") && root.getAsJsonObject("data").has("token"))
                return root.getAsJsonObject("data").get("token").getAsString();
        } catch (Exception e) { Log.w(TAG, "login skip: " + e.getMessage()); }
        return null;
    }

    private static String encodePath(String path) throws Exception {
        String[] segs = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String seg : segs) {
            if (seg.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = is.read(b)) > 0) bos.write(b, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static Handler main = new Handler(Looper.getMainLooper());
    private static void post(Runnable r) { main.post(r); }
}