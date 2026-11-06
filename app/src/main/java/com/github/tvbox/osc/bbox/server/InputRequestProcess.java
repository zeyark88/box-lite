package com.github.tvbox.osc.bbox.server;

import fi.iki.elonen.NanoHTTPD;

import java.util.Map;

/**
 * @author pj567
 * @date :2021/1/5
 * @description: 响应按键和输入
 */

public class InputRequestProcess implements RequestProcess {
    private RemoteServer remoteServer;

    public InputRequestProcess(RemoteServer remoteServer) {
        this.remoteServer = remoteServer;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if (session.getMethod() == NanoHTTPD.Method.POST) {
            switch (fileName) {
                case "/action":
                    return true;
            }
        }
        return false;
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        DataReceiver mDataReceiver = remoteServer.getDataReceiver();
        switch (fileName) {
            case "/action":
                if (params == null) {
                    params = new java.util.HashMap<String, String>();
                }
                // 参数可能缺失(null)，统一防空，避免远程恶意/残缺请求触发 NPE
                if (mDataReceiver != null) {
                    String action = params.get("do");
                    if (action == null) action = "";
                    switch (action) {
                        case "search": {
                            mDataReceiver.onTextReceived(safeText(params, "word", 128));
                            break;
                        }
                        case "api": {
                            String url = safeUrl(params, "url");
                            if (url.isEmpty()) return badRequest();
                            mDataReceiver.onApiReceived(url);
                            break;
                        }
                        case "store": {
                            String url = safeUrl(params, "url");
                            if (url.isEmpty()) return badRequest();
                            mDataReceiver.onStoreReceived(url);
                            break;
                        }
                        case "live": {
                            String url = safeUrl(params, "url");
                            if (url.isEmpty()) return badRequest();
                            mDataReceiver.onLiveReceived(url);
                            break;
                        }
                        case "epg": {
                            String url = safeUrl(params, "url");
                            if (url.isEmpty()) return badRequest();
                            mDataReceiver.onEpgReceived(url);
                            break;
                        }
                        case "proxy": {
                            String url = safeUrl(params, "url");
                            if (url.isEmpty()) return badRequest();
                            mDataReceiver.onProxyReceived(url);
                            break;
                        }
                        case "push": {
                            // 暂未实现
                            String url = safeUrl(params, "url");
                            if (url.isEmpty()) return badRequest();
                            mDataReceiver.onPushReceived(url);
                            break;
                        }
                    }
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "ok");
            default:
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
    }

    private static NanoHTTPD.Response badRequest() {
        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid url");
    }

    /** 取参数并 trim，缺失时返回空串（防 NPE）。 */
    private static String nz(Map<String, String> params, String key) {
        String v = params.get(key);
        return v == null ? "" : v.trim();
    }

    /** 限长文本，防止超长关键字打爆搜索链路。 */
    private static String safeText(Map<String, String> params, String key, int maxLen) {
        String v = nz(params, key);
        return v.length() > maxLen ? v.substring(0, maxLen) : v;
    }

    /**
     * 配置地址校验（B-05）：
     * 本接口可让局域网设备更换数据源，而数据源会触发爬虫 jar 下载并动态执行，
     * 因此必须限制协议与长度，避免被塞入本地路径/异常 scheme。
     */
    private static String safeUrl(Map<String, String> params, String key) {
        String v = nz(params, key);
        if (v.isEmpty() || v.length() > 2048) return "";
        String lower = v.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("clan://") || lower.startsWith("file://")) {
            return v;
        }
        return "";
    }
}
