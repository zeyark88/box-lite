package com.github.tvbox.osc.bbox.util.dlna;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 轻量 DLNA 投屏控制点（标准 UPnP，无第三方库）。 */
public class DlnaController {
    private static final String TAG = "DlnaController";
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String AVT_URN = "urn:schemas-upnp-org:service:AVTransport:1";
    private static String probeStatus = ""; // 最近一次投屏前探测状态(诊断用)

    public interface ScanCallback { void onFound(List<DlnaDevice> devices); void onError(String msg); }
    public interface CastCallback { void onSuccess(); void onError(String msg); }

    public static class DlnaDevice {
        public String name;
        public String location;
        public String controlUrl;
        @Override public String toString() { return name; }
    }

    public static void scan(Context ctx, final int timeoutMs, final ScanCallback cb) {
        // Android 收组播需 MulticastLock
        android.net.wifi.WifiManager.MulticastLock lock = null;
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            lock = wifi.createMulticastLock("dlna_scan_lock");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (Exception ignore) {}
        final android.net.wifi.WifiManager.MulticastLock fLock = lock;
        new Thread(() -> {
            try {
                List<DlnaDevice> list = new ArrayList<>();
                DatagramSocket sock = new DatagramSocket();
                sock.setSoTimeout(1000);
                InetAddress group = InetAddress.getByName(SSDP_ADDR);
                String[] sts = {
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:device:MediaRenderer",
                    "upnp:rootdevice",
                    "ssdp:all"
                };
                // 本机 IPv4(用于同机单播 + 网段扫描)
                List<java.net.Inet4Address> locals = new ArrayList<>();
                try {
                    java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
                    while (nis.hasMoreElements()) {
                        java.net.NetworkInterface ni = nis.nextElement();
                        if (!ni.isUp()) continue;
                        java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                        while (as.hasMoreElements()) {
                            java.net.InetAddress a = as.nextElement();
                            if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) locals.add((java.net.Inet4Address) a);
                        }
                    }
                } catch (Exception ignore) {}
                final String[] fSts = sts;
                final List<java.net.Inet4Address> fLocals = locals;
                java.util.Set<String> swept = new java.util.HashSet<>();
                long deadline = System.currentTimeMillis() + timeoutMs;
                byte[] rbuf = new byte[4096];
                long lastSend = 0; boolean sweptOnce = false;
                while (System.currentTimeMillis() < deadline) {
                    long now = System.currentTimeMillis();
                    if (now - lastSend > 1500) {
                        lastSend = now;
                        // 1) 组播 M-SEARCH(4 种 ST)
                        for (String st : fSts) {
                            String req = "M-SEARCH * HTTP/1.1\r\nHOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: " + st + "\r\n\r\n";
                            byte[] buf = req.getBytes("UTF-8");
                            sock.send(new DatagramPacket(buf, buf.length, group, SSDP_PORT));
                        }
                        // 2) 本机单播(同机接收端场景, 如本机投屏App)
                        try { sendUnicast(sock, InetAddress.getByName("127.0.0.1")); } catch (Exception ignore) {}
                        for (java.net.Inet4Address a : fLocals) sendUnicast(sock, a);
                    }
                    // 3) 网段直扫(仅一轮, /24 全量单播, 彻底绕开组播不可靠)
                    if (!sweptOnce && !fLocals.isEmpty()) {
                        sweptOnce = true;
                        for (java.net.Inet4Address a : fLocals) {
                            String ip = a.getHostAddress();
                            int p = ip.lastIndexOf('.');
                            if (p <= 0) continue;
                            String prefix = ip.substring(0, p + 1);
                            for (int i = 1; i <= 254; i++) {
                                if (swept.contains(prefix + i)) continue;
                                swept.add(prefix + i);
                                try { sendUnicast(sock, InetAddress.getByName(prefix + i)); } catch (Exception ignore) {}
                            }
                        }
                    }
                    DatagramPacket in = new DatagramPacket(rbuf, rbuf.length);
                    try { sock.receive(in); } catch (SocketTimeoutException e) { continue; }
                    String text = new String(in.getData(), 0, in.getLength(), "UTF-8");
                    String loc = extractHeader(text, "LOCATION");
                    if (loc == null || !loc.startsWith("http")) continue;
                    DlnaDevice d = new DlnaDevice();
                    d.location = loc;
                    if (probeDescription(d) && !containsLocation(list, loc)) list.add(d);
                }
                sock.close();
                post(() -> cb.onFound(list));
            } catch (Exception e) {
                Log.e(TAG, "scan err", e);
                post(() -> cb.onError(e.getMessage()));
            } finally {
                try { if (fLock != null && fLock.isHeld()) fLock.release(); } catch (Exception ignore) {}
            }
        }).start();
    }

    /** 单播 M-SEARCH 直发目标 :1900(SSDP unicast discovery, 不依赖组播) */
    private static void sendUnicast(DatagramSocket sock, InetAddress target) {
        try {
            String req = "M-SEARCH * HTTP/1.1\r\nHOST: " + target.getHostAddress() + ":1900\r\nMAN: \"ssdp:discover\"\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nMX: 3\r\n\r\n";
            byte[] buf = req.getBytes("UTF-8");
            sock.send(new DatagramPacket(buf, buf.length, target, 1900));
        } catch (Exception ignore) {}
    }

    private static boolean probeDescription(DlnaDevice d) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(d.location).openConnection();
            c.setConnectTimeout(3000); c.setReadTimeout(3000);
            c.setRequestProperty("Accept", "*/*");
            String xml = readAll(c.getInputStream());
            String ctrl = extractServiceControlUrl(xml);
            if (ctrl == null) { c.disconnect(); return false; }
            d.controlUrl = resolveUrl(d.location, ctrl);
            String fn = extractTag(xml, "friendlyName");
            d.name = (fn == null || fn.trim().isEmpty()) ? d.location : fn.trim();
            c.disconnect();
            return true;
        } catch (Exception e) { return false; }
    }

    /** 投屏(兼容旧调用): 不带请求头 */
    public static void castTo(DlnaDevice d, String videoUrl, CastCallback cb) {
        castTo(d, videoUrl, null, cb);
    }

    /** 投屏: videoUrl 为源直链; headers 为播放器已带请求头(Referer/UA/Cookie 等, 可为 null) */
    public static void castTo(DlnaDevice d, String videoUrl, HashMap<String, String> headers, CastCallback cb) {
        new Thread(() -> {
            try {
                // 投屏前探测: 跟随重定向拿最终直链。探测仅用于诊断, 任何失败都不中止投屏,
                // 降级用原始直链继续(防盗链/自签名https/超时等场景电视端仍可能自行拉到流)。
                String finalUrl = videoUrl;
                try {
                    HttpURLConnection pc = (HttpURLConnection) new URL(videoUrl).openConnection();
                    pc.setConnectTimeout(6000); pc.setReadTimeout(8000);
                    pc.setInstanceFollowRedirects(true);
                    pc.setRequestProperty("User-Agent", headers != null && headers.get("User-Agent") != null ? headers.get("User-Agent") : "Mozilla/5.0");
                    pc.setRequestProperty("Range", "bytes=0-1");
                    if (headers != null) {
                        for (Map.Entry<String, String> e : headers.entrySet()) {
                            String k = e.getKey(); String v = e.getValue();
                            if (k != null && v != null && !"User-Agent".equalsIgnoreCase(k)) pc.setRequestProperty(k, v);
                        }
                    }
                    int code = pc.getResponseCode();
                    probeStatus = "HTTP " + code;
                    if (code >= 200 && code < 300) finalUrl = pc.getURL().toString(); // 展平 302 后的最终直链
                    try { pc.getInputStream().close(); } catch (Exception ignore) {}
                    pc.disconnect();
                } catch (Exception probeErr) {
                    // 探测异常(自签名证书/超时/源暂不可达等): 不中止, 直接按原直链投屏
                    Log.w(TAG, "直链探测跳过(不影响投屏): " + probeErr.getMessage());
                }
                String ctrl = d.controlUrl;
                if (!ctrl.startsWith("http")) ctrl = resolveUrl(d.location, ctrl);
                String body1 = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
                        + "<s:Body><u:SetAVTransportURI xmlns:u=\"" + AVT_URN + "\">"
                        + "<InstanceID>0</InstanceID><CurrentURI>" + esc(finalUrl) + "</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
                        + "</u:SetAVTransportURI></s:Body></s:Envelope>";
                String body2 = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
                        + "<s:Body><u:Play xmlns:u=\"" + AVT_URN + "\">"
                        + "<InstanceID>0</InstanceID><Speed>1</Speed>"
                        + "</u:Play></s:Body></s:Envelope>";
                String r1 = soapPost(ctrl, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", body1);
                String r2 = soapPost(ctrl, "urn:schemas-upnp-org:service:AVTransport:1#Play", body2);
                boolean ok = (r1 != null) && (r2 != null);
                if (ok) post(() -> cb.onSuccess()); else post(() -> cb.onError("投屏失败（设备无响应）" + (probeStatus.isEmpty() ? "" : "；探测=" + probeStatus)));
            } catch (Exception e) {
                Log.e(TAG, "cast err", e);
                post(() -> cb.onError(e.getMessage()));
            }
        }).start();
    }

    public interface PosCallback { void onResult(String relTime, String duration); void onError(String msg); }

    /** 暂停投屏 */
    public static void pauseCast(DlnaDevice d, CastCallback cb) { avAction(d, "Pause", null, cb); }

    /** 继续投屏 */
    public static void resumeCast(DlnaDevice d, CastCallback cb) { avAction(d, "Play", "<Speed>1</Speed>", cb); }

    /** 进度跳转(target 为 H:MM:SS 相对时间) */
    public static void seekCast(DlnaDevice d, String target, CastCallback cb) { avAction(d, "Seek", "<Unit>REL_TIME</Unit><Target>" + target + "</Target>", cb); }

    /** 查询播放进度 */
    public static void getPosition(DlnaDevice d, final PosCallback cb) {
        new Thread(() -> {
            try {
                String ctrl = d.controlUrl;
                if (!ctrl.startsWith("http")) ctrl = resolveUrl(d.location, ctrl);
                String body = soapBody("GetPositionInfo", "<InstanceID>0</InstanceID>");
                String resp = soapRequest(ctrl, "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo", body);
                if (resp == null) { post(() -> cb.onError("无响应")); return; }
                String rel = extractTag(resp, "RelTime");
                String dur = extractTag(resp, "TrackDuration");
                post(() -> cb.onResult(rel == null ? "0:00:00" : rel, dur == null ? "" : dur));
            } catch (Exception e) { post(() -> cb.onError(e.getMessage())); }
        }).start();
    }

    /** 通用 AVTransport 动作 */
    private static void avAction(DlnaDevice d, String action, String extra, CastCallback cb) {
        new Thread(() -> {
            try {
                String ctrl = d.controlUrl;
                if (!ctrl.startsWith("http")) ctrl = resolveUrl(d.location, ctrl);
                String body = soapBody(action, "<InstanceID>0</InstanceID>" + (extra == null ? "" : extra));
                String r = soapPost(ctrl, "urn:schemas-upnp-org:service:AVTransport:1#" + action, body);
                if (r != null) post(() -> cb.onSuccess()); else post(() -> cb.onError(action + " 无响应"));
            } catch (Exception e) { post(() -> cb.onError(e.getMessage())); }
        }).start();
    }

    private static String soapBody(String action, String args) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
                + "<s:Body><u:" + action + " xmlns:u=\"" + AVT_URN + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>";
    }

    /** soapPost 但返回响应体(查询用) */
    private static String soapRequest(String url, String action, String bodyXml) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000); c.setReadTimeout(8000);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + action + "\"");
            c.setDoOutput(true);
            c.getOutputStream().write(bodyXml.getBytes("UTF-8"));
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { c.disconnect(); return null; }
            String resp = readAll(c.getInputStream());
            c.disconnect();
            return resp;
        } catch (Exception e) { return null; }
    }

    /** H:MM:SS 秒数 */
    public static int toSeconds(String hhmmss) {
        try {
            if (hhmmss == null) return 0;
            String[] p = hhmmss.split(":");
            int h = p.length > 0 ? Integer.parseInt(p[0]) : 0;
            int m = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            int sec = p.length > 2 ? Integer.parseInt(p[2].split("\\.")[0]) : 0;
            return h * 3600 + m * 60 + sec;
        } catch (Exception e) { return 0; }
    }

    /** 秒数转 H:MM:SS */
    public static String toTime(int sec) {
        if (sec < 0) sec = 0;
        return String.format(java.util.Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    /** 断开投屏(标准 UPnP Stop) */
    public static void stopCast(DlnaDevice d, CastCallback cb) {
        new Thread(() -> {
            try {
                String ctrl = d.controlUrl;
                if (!ctrl.startsWith("http")) ctrl = resolveUrl(d.location, ctrl);
                String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
                        + "<s:Body><u:Stop xmlns:u=\"" + AVT_URN + "\">"
                        + "<InstanceID>0</InstanceID>"
                        + "</u:Stop></s:Body></s:Envelope>";
                String r = soapPost(ctrl, "urn:schemas-upnp-org:service:AVTransport:1#Stop", body);
                if (r != null) post(() -> cb.onSuccess()); else post(() -> cb.onError("断开失败（设备无响应）"));
            } catch (Exception e) {
                Log.e(TAG, "stop err", e);
                post(() -> cb.onError(e.getMessage()));
            }
        }).start();
    }

    private static String soapPost(String url, String action, String bodyXml) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000); c.setReadTimeout(8000);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + action + "\"");
            c.setDoOutput(true);
            c.getOutputStream().write(bodyXml.getBytes("UTF-8"));
            int code = c.getResponseCode();
            c.disconnect();
            return (code >= 200 && code < 300) ? "" : null;
        } catch (Exception e) { return null; }
    }

    private static String readAll(InputStream is) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    private static String extractServiceControlUrl(String xml) {
        try {
            XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
            p.setInput(new StringReader(xml));
            boolean inAvt = false;
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if ("serviceType".equals(n)) {
                        String t = p.nextText();
                        if (t != null && t.contains("AVTransport")) inAvt = true;
                    } else if (inAvt && "controlURL".equals(n)) {
                        return p.nextText();
                    }
                }
            }
        } catch (Exception e) { }
        return null;
    }

    private static String extractTag(String xml, String tag) {
        // 兼容大小写差异与命名空间前缀(如 <av:RelTime>), 兼容不标准设备
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "<(?:[A-Za-z0-9_-]+:)?(" + java.util.regex.Pattern.quote(tag) + ")[^>]*>(.*?)</(?:[A-Za-z0-9_-]+:)?\\1\\s*>",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(xml);
            return m.find() ? m.group(2).trim() : null;
        } catch (Exception e) { return null; }
    }

    private static String resolveUrl(String base, String path) {
        try {
            Uri u = Uri.parse(base);
            String p = path.startsWith("/") ? path : "/" + path;
            return u.buildUpon().path(p).build().toString();
        } catch (Exception e) { return base; }
    }

    private static String extractHeader(String text, String key) {
        for (String line : text.split("\r\n")) {
            int i = line.indexOf(":");
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase(key))
                return line.substring(i + 1).trim();
        }
        return null;
    }

    private static boolean containsLocation(List<DlnaDevice> l, String loc) {
        for (DlnaDevice d : l) if (d.location != null && d.location.equals(loc)) return true;
        return false;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Handler main = new Handler(Looper.getMainLooper());
    private static void post(Runnable r) { main.post(r); }
}
