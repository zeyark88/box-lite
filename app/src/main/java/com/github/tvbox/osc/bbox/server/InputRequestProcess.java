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
                // 参数可能缺失(null)，统一防空，避免远程恶意/残缺请求触发 NPE
                if (mDataReceiver != null) {
                    String action = params.get("do");
                    switch (action) {
                        case "search": {
                            mDataReceiver.onTextReceived(nz(params, "word"));
                            break;
                        }
                        case "api": {
                            mDataReceiver.onApiReceived(nz(params, "url"));
                            break;
                        }
                        case "store": {
                            mDataReceiver.onStoreReceived(nz(params, "url"));
                            break;
                        }
                        case "live": {
                            mDataReceiver.onLiveReceived(nz(params, "url"));
                            break;
                        }
                        case "epg": {
                            mDataReceiver.onEpgReceived(nz(params, "url"));
                            break;
                        }
                        case "proxy": {
                            mDataReceiver.onProxyReceived(nz(params, "url"));
                            break;
                        }
                        case "push": {
                            // 暂未实现
                            mDataReceiver.onPushReceived(nz(params, "url"));
                            break;
                        }
                    }
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "ok");
            default:
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
    }

    /** 取参数并 trim，缺失时返回空串（防 NPE）。 */
    private static String nz(Map<String, String> params, String key) {
        String v = params.get(key);
        return v == null ? "" : v.trim();
    }
}
