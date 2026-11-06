package com.github.tvbox.osc.bbox.server;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.util.Base64;

import com.github.tvbox.osc.bbox.R;
import com.github.tvbox.osc.bbox.api.ApiConfig;
import com.github.tvbox.osc.bbox.base.App;
import com.github.tvbox.osc.bbox.event.RefreshEvent;
import com.github.tvbox.osc.bbox.event.ServerEvent;
import com.github.tvbox.osc.bbox.util.FileUtils;
import com.github.tvbox.osc.bbox.util.OkGoHelper;
import com.github.tvbox.osc.bbox.util.Proxy;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.lzy.okgo.model.Response;
import org.greenrobot.eventbus.EventBus;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import fi.iki.elonen.NanoHTTPD;

import static com.github.tvbox.osc.bbox.util.RegexUtils.getPattern;

/**
 * @author pj567
 * @date :2021/1/5
 * @description:
 */
public class RemoteServer extends NanoHTTPD {
    /**
     * 安全加固（B-01）：局域网控制接口访问令牌。
     * 仅对「非本机来源」的 HTML 页面与写操作/文件接口生效；
     * 本机（127.0.0.1 / 本机网卡地址）请求一律放行，保证播放器与 clan:// 本地链路不受影响。
     */
    public static String accessToken = "";
    private static final String PREF_NAME = "tvbox_server_security";
    private static final String PREF_TOKEN = "access_token";
    private static final String TOKEN_HEADER = "x-tv-token";

    /** 解压/上传的硬上限，防止 zip bomb 打爆存储（512MB）。 */
    private static final long MAX_EXTRACT_BYTES = 512L * 1024 * 1024;

    private Context mContext;
    public static int serverPort = 9978;
    private boolean isStarted = false;
    private DataReceiver mDataReceiver;
    public static String m3u8Content;
    private ArrayList<RequestProcess> getRequestList = new ArrayList<>();
    private ArrayList<RequestProcess> postRequestList = new ArrayList<>();

    public RemoteServer(int port, Context context) {
        super(port);
        mContext = context;
        initToken();
        addGetRequestProcess();
        addPostRequestProcess();
    }

    /**
     * 令牌持久化：首次启动生成 32 位十六进制，之后复用，保证 TV 端二维码地址稳定可用。
     */
    private void initToken() {
        try {
            SharedPreferences sp = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String token = sp.getString(PREF_TOKEN, "");
            if (token == null || token.length() < 16) {
                token = UUID.randomUUID().toString().replace("-", "");
                sp.edit().putString(PREF_TOKEN, token).commit();
            }
            accessToken = token;
        } catch (Throwable th) {
            // 极端情况下退化到进程内令牌，仍优于完全无鉴权
            if (accessToken == null || accessToken.isEmpty()) {
                accessToken = UUID.randomUUID().toString().replace("-", "");
            }
        }
    }

    private void addGetRequestProcess() {
        getRequestList.add(new RawRequestProcess(this.mContext, "/", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/index.html", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/style.css", R.raw.style, "text/css"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/ui.css", R.raw.ui, "text/css"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/jquery.js", R.raw.jquery, "application/x-javascript"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/script.js", R.raw.script, "application/x-javascript"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/favicon.ico", R.drawable.app_icon, "image/x-icon"));
    }

    private void addPostRequestProcess() {
        postRequestList.add(new InputRequestProcess(this));
    }

    @Override
    public void start(int timeout, boolean daemon) throws IOException {
        isStarted = true;
        super.start(timeout, daemon);
        EventBus.getDefault().post(new ServerEvent(ServerEvent.SERVER_SUCCESS));
    }

    @Override
    public void stop() {
        super.stop();
        isStarted = false;
    }

    private Response getProxy(Object[] rs){
        InputStream stream = null;
        try {
            if (rs[0] instanceof NanoHTTPD.Response) return markError((NanoHTTPD.Response) rs[0]);
            int code = (int) rs[0];
            String mime = (String) rs[1];
            stream = rs[2] != null ? (InputStream) rs[2] : null;
            Response response = NanoHTTPD.newChunkedResponse(
                    Response.Status.lookup(code),
                    mime,
                    stream
            );
            // 添加头部信息
            if (rs.length >= 4 && rs[3] instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> mapHeader = (Map<String, String>) rs[3];
                if(!mapHeader.isEmpty()){
                    for (String key : mapHeader.keySet()) {
                        response.addHeader(key, mapHeader.get(key));
                    }
                }
            }
            return markError(response);
        } catch (Throwable th) {
            closeFailedStream(stream, th);
            return markError(NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "500"));
        }
    }

    /**
     * 对齐 FongMi/TV：上游返回 >= 400 时打上 X-TV-Proxy-Error: 1，
     * 让播放器侧能区分「代理失败」与「空响应」。
     */
    private static Response markError(Response response) {
        if (response != null && response.getStatus() != null
                && response.getStatus().getRequestStatus() >= 400) {
            response.addHeader("X-TV-Proxy-Error", "1");
        }
        return response;
    }

    /**
     * 构造响应失败时关闭已打开的流，避免 fd/连接泄漏；
     * 关闭异常以 suppressed 挂到原始异常上（addSuppressed 需 API 19+，低版本静默忽略）。
     */
    private static void closeFailedStream(InputStream stream, Throwable error) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException | RuntimeException | Error failure) {
            if (failure != error && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                error.addSuppressed(failure);
            }
        }
    }

    // ==================== 安全加固：来源与令牌校验 ====================

    /** 判断请求是否来自本机（回环地址或本机网卡地址）。 */
    private boolean isLocalRequest(IHTTPSession session) {
        try {
            String ip = session.getRemoteIpAddress();
            if (ip == null) return false;
            if (ip.startsWith("::ffff:")) ip = ip.substring(7);
            if ("127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return true;
            String local = getLocalIPAddress(mContext);
            return local != null && local.equals(ip);
        } catch (Throwable th) {
            return false;
        }
    }

    /** 校验令牌：支持自定义请求头 X-TV-Token（页面 ajax）与查询参数 tk（扫码首次进入）。 */
    private boolean checkToken(IHTTPSession session) {
        if (accessToken == null || accessToken.isEmpty()) return false;
        try {
            String tk = session.getHeaders().get(TOKEN_HEADER);
            if (tk == null || tk.isEmpty()) {
                Map<String, String> parms = session.getParms();
                if (parms != null) tk = parms.get("tk");
            }
            return accessToken.equals(tk);
        } catch (Throwable th) {
            return false;
        }
    }

    private static boolean isStaticResource(String uri) {
        return "/style.css".equals(uri) || "/ui.css".equals(uri) || "/jquery.js".equals(uri)
                || "/script.js".equals(uri) || "/favicon.ico".equals(uri);
    }

    private static boolean isIndexPage(String uri) {
        return "/".equals(uri) || "/index.html".equals(uri);
    }

    /** 需要令牌的敏感接口（写操作 + 文件浏览 + 推送）。 */
    private static boolean needsToken(String uri) {
        return "/action".equals(uri) || "/upload".equals(uri) || "/newFolder".equals(uri)
                || "/delFolder".equals(uri) || "/delFile".equals(uri) || uri.startsWith("/file/")
                || uri.startsWith("/push/");
    }

    /** 输出首页，并按是否已授权决定是否注入访问令牌。 */
    private Response serveIndexPage(boolean withToken) {
        try {
            InputStream is = mContext.getResources().openRawResource(R.raw.index);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
            is.close();
            String html = new String(bos.toByteArray(), "UTF-8");
            String inject = "<script>window.__TVBOX_TK__='" + (withToken ? accessToken : "") + "';</script>";
            int idx = html.indexOf("<head>");
            if (idx >= 0) {
                html = html.substring(0, idx + 6) + inject + html.substring(idx + 6);
            } else {
                html = inject + html;
            }
            byte[] data = html.getBytes("UTF-8");
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                    new ByteArrayInputStream(data), data.length);
        } catch (Throwable th) {
            return createPlainTextResponse(Response.Status.INTERNAL_ERROR, "index load failed");
        }
    }

    /** 未授权访问首页时返回的提示页（不泄露令牌）。 */
    private Response serveUnauthorizedPage() {
        String html = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>需要授权</title></head><body style=\"font-family:sans-serif;padding:32px;line-height:1.8\">"
                + "<h2>需要遥控授权</h2>"
                + "<p>请在电视端打开「远程控制 / 遥控」弹窗，用手机扫描二维码访问。</p>"
                + "<p style=\"color:#888\">直接输入电视 IP 地址无法使用遥控功能，这是本应用的安全策略。</p>"
                + "</body></html>";
        try {
            byte[] data = html.getBytes("UTF-8");
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/html; charset=utf-8",
                    new ByteArrayInputStream(data), data.length);
        } catch (Throwable th) {
            return createPlainTextResponse(Response.Status.UNAUTHORIZED, "unauthorized");
        }
    }

    /**
     * 路径安全（B-02/B-03/B-04）：把相对路径解析到 root 之下，越界返回 null。
     * 使用 getCanonicalPath 前缀比对，兼容 minSdk 16（不使用 java.nio.file）。
     */
    static File resolveWithin(File root, String relative) {
        if (root == null) return null;
        String rel = relative == null ? "" : relative;
        // 去掉前导分隔符，避免 new File(parent, "/abs") 忽略 parent
        while (rel.startsWith("/") || rel.startsWith("\\")) {
            rel = rel.substring(1);
        }
        try {
            String canonicalRoot = root.getCanonicalPath();
            File target = new File(root, rel);
            String canonicalTarget = target.getCanonicalPath();
            if (canonicalTarget.equals(canonicalRoot)) return target;
            if (canonicalTarget.startsWith(canonicalRoot + File.separator)) return target;
            return null;
        } catch (Throwable th) {
            return null;
        }
    }

    static File externalRoot() {
        try {
            return new File(Environment.getExternalStorageDirectory().getCanonicalPath());
        } catch (Throwable th) {
            return Environment.getExternalStorageDirectory();
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        EventBus.getDefault().post(new ServerEvent(ServerEvent.SERVER_CONNECTION));
        String rawUri = session.getUri() == null ? "" : session.getUri().trim();
        int qIdx = rawUri.indexOf('?');
        if (qIdx >= 0) rawUri = rawUri.substring(0, qIdx);

        boolean local = isLocalRequest(session);
        if (!local) {
            // 首页：必须携带有效令牌才下发完整页面，否则只给提示页
            if (isIndexPage(rawUri)) {
                return checkToken(session) ? serveIndexPage(true) : serveUnauthorizedPage();
            }
            // 静态资源（css/js/图标）不含敏感信息，放行以免页面白屏
            if (isStaticResource(rawUri)) {
                for (RequestProcess process : getRequestList) {
                    if (process.isRequest(session, rawUri)) {
                        return process.doResponse(session, rawUri, session.getParms(), null);
                    }
                }
            }
            // 敏感接口必须携带令牌
            if (needsToken(rawUri) && !checkToken(session)) {
                return createPlainTextResponse(Response.Status.UNAUTHORIZED, "unauthorized");
            }
        }
        if (!session.getUri().isEmpty()) {
            String fileName = session.getUri().trim();
            if (fileName.indexOf('?') >= 0) {
                fileName = fileName.substring(0, fileName.indexOf('?'));
            }
            if (session.getMethod() == Method.GET) {
                for (RequestProcess process : getRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.getParms(), null);
                    }
                }
                if (fileName.equals("/proxy")) {
                    Map<String, String> params = session.getParms();
                    params.putAll(session.getHeaders());
                    if (params.containsKey("do")) {
                        Object[] rs = ApiConfig.get().proxyLocal(params);
                        return getProxy(rs);
                    }
                    if (params.containsKey("go")) {
                        Object[] rs = Proxy.proxy(params);
                        return getProxy(rs);
                    }
                } else if (fileName.startsWith("/file/")) {
                    try {
                        String f = fileName.substring(6);
                        File rootFile = externalRoot();
                        File localFile = resolveWithin(rootFile, f);
                        if (localFile == null) {
                            // 越界访问（如 ../../）直接拒绝
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "forbidden");
                        }
                        if (localFile.exists()) {
                            if (localFile.isFile()) {
                                return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", new FileInputStream(localFile));
                            } else {
                                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, fileList(rootFile, f));
                            }
                        } else {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "File " + localFile.getAbsolutePath() + " not found!");
                        }
                    } catch (Throwable th) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, th.getMessage());
                    }
                } else if (fileName.equals("/dns-query")) {
                    String name = session.getParms().get("name");
                    byte[] rs = null;
                    try {
                        rs = OkGoHelper.dnsOverHttps.lookupHttpsForwardSync(name);
                    } catch (Throwable th) {
                        rs = new byte[0];
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/dns-message", new ByteArrayInputStream(rs), rs.length);
                } else if (fileName.startsWith("/push/")) {
                    String url = fileName.substring(6);
                    if (url.startsWith("b64:")) {
                        try {
                            url = new String(Base64.decode(url.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                        } catch (UnsupportedEncodingException e) {
                            e.printStackTrace();
                        }
                    } else {
                        url = URLDecoder.decode(url);
                    }
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PUSH_URL, url));
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "ok");
                }  else if (fileName.startsWith("/proxyM3u8")) {
//                    com.github.tvbox.osc.bbox.util.LOG.i("echo-m3u8:"+m3u8Content);
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, m3u8Content);
                }
                else if (fileName.startsWith("/dash/")) {
                    String dashData = App.getInstance().getDashData();
                    try {
                        String data = new String(Base64.decode(dashData, Base64.DEFAULT | Base64.NO_WRAP), "UTF-8");
                        return NanoHTTPD.newFixedLengthResponse(
                                Response.Status.OK,
                                "application/dash+xml",
                                data
                        );
                    } catch (Throwable th) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, dashData);
                    }
                }
            } else if (session.getMethod() == Method.POST) {
                Map<String, String> files = new HashMap<String, String>();
                try {
                    if (session.getHeaders().containsKey("content-type")) {
                        String hd = session.getHeaders().get("content-type");
                        if (hd != null) {
                            // cuke: 修正中文乱码问题
                            if (hd.toLowerCase().contains("multipart/form-data") && !hd.toLowerCase().contains("charset=")) {
                                Matcher matcher = getPattern("[ |\t]*(boundary[ |\t]*=[ |\t]*['|\"]?[^\"^'^;^,]*['|\"]?)", Pattern.CASE_INSENSITIVE).matcher(hd);
                                String boundary = matcher.find() ? matcher.group(1) : null;
                                if (boundary != null) {
                                    session.getHeaders().put("content-type", "multipart/form-data; charset=utf-8; " + boundary);
                                }
                            }
                        }
                    }
                    session.parseBody(files);
                } catch (IOException IOExc) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + IOExc.getMessage());
                } catch (NanoHTTPD.ResponseException rex) {
                    return createPlainTextResponse(rex.getStatus(), rex.getMessage());
                }
                for (RequestProcess process : postRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.getParms(), files);
                    }
                }
                try {
                    Map<String, String> params = session.getParms();
                    if (fileName.equals("/upload")) {
                        String path = params.get("path");
                        File rootFile = externalRoot();
                        File destDir = resolveWithin(rootFile, path);
                        if (destDir == null) {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "forbidden");
                        }
                        for (String k : files.keySet()) {
                            if (k.startsWith("files-")) {
                                String fn = params.get(k);
                                String tmpFile = files.get(k);
                                File tmp = new File(tmpFile);
                                // 只取纯文件名，丢弃客户端可能携带的目录部分
                                String safeName = new File(fn == null ? "" : fn).getName();
                                if (safeName.isEmpty()) continue;
                                File file = resolveWithin(destDir, safeName);
                                if (file == null) continue;
                                if (file.exists())
                                    file.delete();
                                if (tmp.exists()) {
                                    if (safeName.toLowerCase().endsWith(".zip")) {
                                        unzip(tmp, destDir);
                                    } else {
                                        FileUtils.copyFile(tmp, file);
                                    }
                                }
                                if (tmp.exists())
                                    tmp.delete();
                            }
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                    } else if (fileName.equals("/newFolder")) {
                        String path = params.get("path");
                        String name = params.get("name");
                        File rootFile = externalRoot();
                        File destDir = resolveWithin(rootFile, path);
                        if (destDir == null) {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "forbidden");
                        }
                        String safeName = new File(name == null ? "" : name).getName();
                        if (safeName.isEmpty()) {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                        }
                        File file = resolveWithin(destDir, safeName);
                        if (file == null) {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "forbidden");
                        }
                        if (!file.exists()) {
                            file.mkdirs();
                            File flag = new File(file, ".tvbox_folder");
                            if (!flag.exists())
                                flag.createNewFile();
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                    } else if (fileName.equals("/delFolder")) {
                        String path = params.get("path");
                        File rootFile = externalRoot();
                        File file = resolveWithin(rootFile, path);
                        // 禁止删除外部存储根目录
                        if (file == null || isSameFile(file, rootFile)) {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "forbidden");
                        }
                        if (file.exists()) {
                            FileUtils.recursiveDelete(file);
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                    } else if (fileName.equals("/delFile")) {
                        String path = params.get("path");
                        File rootFile = externalRoot();
                        File file = resolveWithin(rootFile, path);
                        if (file == null || isSameFile(file, rootFile)) {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "forbidden");
                        }
                        if (file.exists() && file.isFile()) {
                            file.delete();
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                    }
                } catch (Throwable th) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                }
            }
        }
        //default page: index.html
        return getRequestList.get(0).doResponse(session, "", null, null);
    }

    public void setDataReceiver(DataReceiver receiver) {
        mDataReceiver = receiver;
    }

    public DataReceiver getDataReceiver() {
        return mDataReceiver;
    }

    public boolean isStarting() {
        return isStarted;
    }

    public String getServerAddress() {
        String ipAddress = getLocalIPAddress(mContext);
        return "http://" + ipAddress + ":" + RemoteServer.serverPort + "/";
    }

    /**
     * 对外展示（二维码 / 提示文本）用的地址，携带访问令牌，
     * 手机扫码后即可获得遥控权限（B-01）。
     */
    public String getServerAddressWithToken() {
        return getServerAddress() + "?tk=" + accessToken;
    }

    private static boolean isSameFile(File a, File b) {
        if (a == null || b == null) return false;
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Throwable th) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    public String getLoadAddress() {
        return "http://127.0.0.1:" + RemoteServer.serverPort + "/";
    }

    public static Response createPlainTextResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text);
    }

    public static Response createJSONResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, "application/json", text);
    }

    @SuppressLint("DefaultLocale")
    public static String getLocalIPAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        if (ipAddress == 0) {
            try {
                Enumeration<NetworkInterface> enumerationNi = NetworkInterface.getNetworkInterfaces();
                while (enumerationNi.hasMoreElements()) {
                    NetworkInterface networkInterface = enumerationNi.nextElement();
                    String interfaceName = networkInterface.getDisplayName();
                    if (interfaceName.equals("eth0") || interfaceName.equals("wlan0")) {
                        Enumeration<InetAddress> enumIpAddr = networkInterface.getInetAddresses();
                        while (enumIpAddr.hasMoreElements()) {
                            InetAddress inetAddress = enumIpAddr.nextElement();
                            if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                                return inetAddress.getHostAddress();
                            }
                        }
                    }
                }
            } catch (SocketException e) {
                e.printStackTrace();
            }
        } else {
            return String.format("%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff), (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
        }
        return "0.0.0.0";
    }

    String fileTime(long time, String fmt) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        Date date = calendar.getTime();
        SimpleDateFormat sdf = new SimpleDateFormat(fmt);
        return sdf.format(date);
    }

    String fileList(File rootFile, String path) {
        String root = rootFile.getAbsolutePath();
        File file = resolveWithin(rootFile, path);
        if (file == null) file = rootFile;
        File[] list = file.listFiles();
        JsonObject info = new JsonObject();
        info.addProperty("remote", getServerAddress().replace("http://", "clan://"));
        info.addProperty("del", 0);
        if (path == null || path.isEmpty()) {
            info.addProperty("parent", ".");
        } else {
            File parentFile = file.getParentFile();
            String parent = parentFile == null ? "." : parentFile.getAbsolutePath().replace(root + "/", "").replace(root, "");
            info.addProperty("parent", parent);
        }
        if (list == null || list.length == 0) {
            info.add("files", new JsonArray());
            return info.toString();
        }
        Arrays.sort(list, new Comparator<File>() {
            @Override
            public int compare(File o1, File o2) {
                if (o1.isDirectory() && o2.isFile()) return -1;
                return o1.isFile() && o2.isDirectory() ? 1 : o1.getName().compareTo(o2.getName());
            }
        });
        JsonArray result = new JsonArray();
        for (File f : list) {
            if (f.getName().startsWith(".")) {
                if (f.getName().equals(".tvbox_folder")) {
                    info.addProperty("del", 1);
                }
                continue;
            }
            JsonObject fileObj = new JsonObject();
            fileObj.addProperty("name", f.getName());
            fileObj.addProperty("path", f.getAbsolutePath().replace(root + "/", ""));
            fileObj.addProperty("time", fileTime(f.lastModified(), "yyyy/MM/dd aHH:mm:ss"));
            fileObj.addProperty("dir", f.isDirectory() ? 1 : 0);
            result.add(fileObj);
        }
        info.add("files", result);
        return info.toString();
    }

    /**
     * 解压（B-03）：逐条校验解压目标必须落在 destDir 之内，防止 zip slip；
     * 同时限制累计写入体积，防止 zip bomb 打爆存储。
     */
    void unzip(File zipFilePath, File destDir) throws Throwable {
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        ZipFile zip = new ZipFile(zipFilePath);
        try {
            Enumeration<ZipEntry> iter = (Enumeration<ZipEntry>) zip.entries();
            long totalBytes = 0;
            while (iter.hasMoreElements()) {
                ZipEntry entry = iter.nextElement();
                File target = resolveWithin(destDir, entry.getName());
                if (target == null) {
                    // 条目路径越界（如 ../../），跳过
                    continue;
                }
                if (entry.isDirectory()) {
                    if (!target.exists())
                        target.mkdirs();
                    File flag = new File(target, ".tvbox_folder");
                    if (!flag.exists())
                        flag.createNewFile();
                    continue;
                }
                InputStream is = zip.getInputStream(entry);
                try {
                    totalBytes += extractFile(is, target, MAX_EXTRACT_BYTES - totalBytes);
                } finally {
                    try {
                        is.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (totalBytes > MAX_EXTRACT_BYTES) {
                    throw new IOException("unzip exceeds size limit");
                }
            }
        } finally {
            try {
                zip.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 写出单个条目，返回写入字节数；超过 budget 立即抛错。
     */
    long extractFile(InputStream inputStream, File dst, long budget) throws Throwable {
        if (dst.exists())
            dst.delete();
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dst));
        long written = 0;
        try {
            byte[] bytesIn = new byte[4096];
            int len;
            while ((len = inputStream.read(bytesIn)) > 0) {
                written += len;
                if (written > budget) {
                    throw new IOException("entry exceeds size limit");
                }
                bos.write(bytesIn, 0, len);
            }
        } finally {
            try {
                bos.close();
            } catch (Throwable ignored) {
            }
        }
        return written;
    }

}
