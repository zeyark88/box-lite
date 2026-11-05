package com.github.tvbox.osc.bbox.util.webdav;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** 极简 WebDAV 客户端：PROPFIND 列目录。 */
public class WebDavClient {
    private static final String TAG = "WebDavClient";

    public static class DavItem {
        public String href;
        public String name;
        public boolean isDir;
        public long size;
        @Override public String toString() { return (isDir ? "[D] " : "[F] ") + name; }
    }

    public interface ListCallback { void onResult(List<DavItem> items); void onError(String msg); }

    public static void list(final String url, final String user, final String pass, final ListCallback cb) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("PROPFIND");
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                c.setRequestProperty("Depth", "1");
                c.setRequestProperty("Content-Type", "application/xml");
                if (user != null && !user.isEmpty()) {
                    String auth = Base64.encodeToString((user + ":" + pass).getBytes("UTF-8"), Base64.NO_WRAP);
                    c.setRequestProperty("Authorization", "Basic " + auth);
                }
                String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                        + "<D:propfind xmlns:D=\"DAV:\"><D:prop>"
                        + "<D:displayname/><D:resourcetype/><D:getcontentlength/>"
                        + "</D:prop></D:propfind>";
                c.setDoOutput(true);
                c.getOutputStream().write(body.getBytes("UTF-8"));
                int code = c.getResponseCode();
                if (code != 207 && code != 200) {
                    String err = "HTTP " + code;
                    post(() -> cb.onError(err));
                    return;
                }
                String xml = readAll(c.getInputStream());
                c.disconnect();
                List<DavItem> items = parse(xml, url);
                post(() -> cb.onResult(items));
            } catch (Exception e) {
                Log.e(TAG, "list err", e);
                post(() -> cb.onError(e.getMessage()));
            }
        }).start();
    }

    private static List<DavItem> parse(String xml, String baseUrl) {
        List<DavItem> out = new ArrayList<>();
        try {
            XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
            p.setInput(new StringReader(xml));
            DavItem cur = null;
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if ("response".equals(n)) cur = new DavItem();
                    else if (cur != null && "href".equals(n)) cur.href = p.nextText();
                    else if (cur != null && "collection".equals(n)) cur.isDir = true;
                    else if (cur != null && "getcontentlength".equals(n)) {
                        try { cur.size = Long.parseLong(p.nextText().trim()); } catch (Exception ignore) {}
                    }
                } else if (ev == XmlPullParser.END_TAG && "response".equals(p.getName())) {
                    if (cur != null && cur.href != null) {
                        cur.name = nameFromHref(cur.href);
                        out.add(cur);
                    }
                    cur = null;
                }
            }
        } catch (Exception e) { }
        if (!out.isEmpty() && out.get(0).isDir) {
            String rootPath = pathOf(baseUrl);
            if (hrefMatches(out.get(0).href, rootPath)) out.remove(0);
        }
        return out;
    }

    private static String nameFromHref(String href) {
        if (href == null) return "";
        String h = href.endsWith("/") ? href.substring(0, href.length() - 1) : href;
        int i = h.lastIndexOf('/');
        return i >= 0 ? h.substring(i + 1) : h;
    }

    private static boolean hrefMatches(String href, String path) {
        String a = href.endsWith("/") ? href : href + "/";
        String b = path.endsWith("/") ? path : path + "/";
        return a.equals(b) || href.equals(path) || href.endsWith(path);
    }

    private static String pathOf(String url) {
        try { return new URL(url).getPath(); } catch (Exception e) { return "/"; }
    }

    public static String join(String base, String href) {
        if (href.startsWith("http")) return href;
        try {
            URL u = new URL(base);
            String p = href.startsWith("/") ? href : "/" + href;
            return u.getProtocol() + "://" + u.getAuthority() + p;
        } catch (Exception e) { return base; }
    }

    public static String playUrl(String fileUrl, String user, String pass) {
        if (user == null || user.isEmpty()) return fileUrl;
        try {
            URL u = new URL(fileUrl);
            return u.getProtocol() + "://" + user + ":" + pass + "@" + u.getAuthority() + u.getPath();
        } catch (Exception e) { return fileUrl; }
    }

    private static String readAll(java.io.InputStream is) throws Exception {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    private static Handler main = new Handler(Looper.getMainLooper());
    private static void post(Runnable r) { main.post(r); }
}
