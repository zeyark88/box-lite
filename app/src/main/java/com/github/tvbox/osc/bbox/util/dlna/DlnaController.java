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
import java.util.List;

/** 轻量 DLNA 投屏控制点（标准 UPnP，无第三方库）。 */
public class DlnaController {
    private static final String TAG = "DlnaController";
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String AVT_URN = "urn:schemas-upnp-org:service:AVTransport:1";

    public interface ScanCallback { void onFound(List<DlnaDevice> devices); void onError(String msg); }
    public interface CastCallback { void onSuccess(); void onError(String msg); }

    public static class DlnaDevice {
        public String name;
        public String location;
        public String controlUrl;
        @Override public String toString() { return name; }
    }

    public static void scan(Context ctx, final int timeoutMs, final ScanCallback cb) {
        new Thread(() -> {
            try {
                List<DlnaDevice> list = new ArrayList<>();
                DatagramSocket sock = new DatagramSocket();
                sock.setSoTimeout(1000);
                String req = "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 3\r\n"
                        + "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
                        + "\r\n";
                byte[] buf = req.getBytes("UTF-8");
                sock.send(new DatagramPacket(buf, buf.length, InetAddress.getByName(SSDP_ADDR), SSDP_PORT));
                long deadline = System.currentTimeMillis() + timeoutMs;
                byte[] rbuf = new byte[4096];
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket in = new DatagramPacket(rbuf, rbuf.length);
                    try { sock.receive(in); } catch (SocketTimeoutException e) { continue; }
                    String text = new String(in.getData(), 0, in.getLength(), "UTF-8");
                    String loc = extractHeader(text, "LOCATION");
                    if (loc == null) continue;
                    DlnaDevice d = new DlnaDevice();
                    d.location = loc;
                    if (probeDescription(d) && !containsLocation(list, loc)) list.add(d);
                }
                sock.close();
                post(() -> cb.onFound(list));
            } catch (Exception e) {
                Log.e(TAG, "scan err", e);
                post(() -> cb.onError(e.getMessage()));
            }
        }).start();
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

    public static void castTo(DlnaDevice d, String videoUrl, CastCallback cb) {
        new Thread(() -> {
            try {
                String ctrl = d.controlUrl;
                if (!ctrl.startsWith("http")) ctrl = resolveUrl(d.location, ctrl);
                String body1 = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
                        + "<s:Body><u:SetAVTransportURI xmlns:u=\"" + AVT_URN + "\">"
                        + "<InstanceID>0</InstanceID><CurrentURI>" + esc(videoUrl) + "</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
                        + "</u:SetAVTransportURI></s:Body></s:Envelope>";
                String body2 = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
                        + "<s:Body><u:Play xmlns:u=\"" + AVT_URN + "\">"
                        + "<InstanceID>0</InstanceID><Speed>1</Speed>"
                        + "</u:Play></s:Body></s:Envelope>";
                String r1 = soapPost(ctrl, "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI", body1);
                String r2 = soapPost(ctrl, "urn:schemas-upnp-org:service:AVTransport:1#Play", body2);
                boolean ok = (r1 != null) && (r2 != null);
                if (ok) post(() -> cb.onSuccess()); else post(() -> cb.onError("投屏失败（设备无响应）"));
            } catch (Exception e) {
                Log.e(TAG, "cast err", e);
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
        try {
            int a = xml.indexOf("<" + tag + ">");
            if (a < 0) return null;
            int b = xml.indexOf("</" + tag + ">", a);
            if (b < 0) return null;
            return xml.substring(a + tag.length() + 2, b);
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
