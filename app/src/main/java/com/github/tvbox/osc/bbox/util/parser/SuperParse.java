package com.github.tvbox.osc.bbox.util.parser;

import android.util.Base64;

import com.github.catvod.crawler.SpiderDebug;
import com.github.tvbox.osc.bbox.util.LOG;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SuperParse {
    /** 1.0.14：改用并发容器。PlayActivity/PlayFragment 各自的单线程解析池可能并发 put，
     *     原 HashMap 并发写存在丢数据风险（仅本类内部使用，改声明类型无外部影响）。 */
    public static final Map<String, ArrayList<String>> flagWebJx = new ConcurrentHashMap<>();
    static HashMap<String, ArrayList<String>> configs = null;
    static LinkedHashMap<String, String> jsonJx = null;
    static ArrayList<String> webJx = null;

    public static JSONObject parse(LinkedHashMap<String, HashMap<String, String>> jx, String flag, String url) {
        try {
            // 每次按当前 jx 重建 configs（原实现仅在首次构建，切换源后配置不会更新）
            configs = new HashMap<>();
            for (Map.Entry<String, HashMap<String, String>> entry : jx.entrySet()) {
                String key = entry.getKey();
                HashMap<String, String> parseBean = entry.getValue();
                if (parseBean == null) {
                    continue;
                }
                String type = parseBean.get("type");
                if (type == null) {
                    continue;
                }
                if ("1".equals(type) || "0".equals(type)) {
                    try {
                        String ext = parseBean.get("ext");
                        if (ext == null) {
                            continue;
                        }
                        JSONArray flagsArray = new JSONObject(ext).getJSONArray("flag");
                        for (int j = 0; j < flagsArray.length(); j++) {
                            String flagKey = flagsArray.getString(j);
                            ArrayList<String> flagJx = configs.get(flagKey);
                            if (flagJx == null) {
                                flagJx = new ArrayList<>();
                                configs.put(flagKey, flagJx);
                            }
                            flagJx.add(key);
                        }
                    } catch (Exception e) {
                        SpiderDebug.log(e);
                    }
                }
            }
            // 注：configs/jsonJx/webJx 仍为类级 static，多线程并发解析会互相覆盖（jsonJx 竞态）。
            //     彻底修复需把 jsonJx 作为参数贯穿调用链（涉及外部调用方），安排在源码补全后的重构阶段。

            // 根据配置构建 jsonJx 和 webJx
            jsonJx = new LinkedHashMap<>();
            webJx = new ArrayList<>();
            List<String> targetKeys = configs.get(flag);
            if (targetKeys != null && !targetKeys.isEmpty()) {
                for (String key : targetKeys) {
                    HashMap<String, String> parseBean = jx.get(key);
                    if (parseBean == null) {
                        continue;
                    }
                    String type = parseBean.get("type");
                    if (type == null) {
                        continue;
                    }
                    if ("1".equals(type)) {
                        String urlValue = parseBean.get("url");
                        String ext = parseBean.get("ext");
                        if (urlValue != null && ext != null) {
                            jsonJx.put(key, mixUrl(urlValue, ext));
                        }
                    } else if ("0".equals(type)) {
                        String urlValue = parseBean.get("url");
                        if (urlValue != null) {
                            webJx.add(urlValue);
                        }
                    }
                }
            } else {
                for (Map.Entry<String, HashMap<String, String>> entry : jx.entrySet()) {
                    String key = entry.getKey();
                    HashMap<String, String> parseBean = entry.getValue();
                    if (parseBean == null) {
                        continue;
                    }
                    String type = parseBean.get("type");
                    if (type == null) {
                        continue;
                    }
                    if ("1".equals(type)) {
                        String urlValue = parseBean.get("url");
                        String ext = parseBean.get("ext");
                        if (urlValue != null && ext != null) {
                            jsonJx.put(key, mixUrl(urlValue, ext));
                        }
                    } else if ("0".equals(type)) {
                        String urlValue = parseBean.get("url");
                        if (urlValue != null) {
                            webJx.add(urlValue);
                        }
                    }
                }
            }
            if (!webJx.isEmpty()) {
                flagWebJx.put(flag, webJx);
            }

            if (!webJx.isEmpty()) {
                JSONObject webResult = new JSONObject();
                webResult.put("url", "proxy://go=SuperParse&flag=" + flag + "&url=" + Base64.encodeToString(url.getBytes(), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP));
                webResult.put("parse", 1);
                webResult.put("ua", Utils.UaWinChrome);
                return webResult;
            }
        } catch (Exception e) {
            LOG.i("echo-result"+e.getMessage());
        }
        return new JSONObject();
    }

    public static JSONObject doJsonJx(LinkedHashMap<String, String>json_jxs,String url){
        LOG.i("echo-jsonJx1"+json_jxs.toString());
        return JsonParallel.parse(json_jxs, url);
    }

    public static JSONObject doJsonJx(String url){
        // 1.0.14：修复 jsonJx 为 null 时 Log 直接 NPE；并快照拷贝一份，
        // 避免 JsonParallel 遍历期间被另一次 parse() 重写导致 ConcurrentModificationException/串配置。
        // 注：jsonJx 本身仍为类级 static（彻底修复需贯穿调用链重构，见 docs/ROADMAP.md）。
        LinkedHashMap<String, String> snapshot = jsonJx;
        if (snapshot == null || snapshot.isEmpty()) {
            LOG.i("echo-jsonJx2 未初始化，跳过");
            return new JSONObject();
        }
        LOG.i("echo-jsonJx2 size=" + snapshot.size());
        return JsonParallel.parse(new LinkedHashMap<>(snapshot), url);
    }

    public static void stopJsonJx(){
        JsonParallel.cancelTasks();
    }

    private static String mixUrl(String url, String ext) {
        if (ext.trim().length() > 0) {
            int idx = url.indexOf("?");
            if (idx > 0) {
                return url.substring(0, idx + 1) + "cat_ext=" + Base64.encodeToString(ext.getBytes(), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP) + "&" + url.substring(idx + 1);
            }
        }
        return url;
    }

    public static Object[] loadHtml(String flag, String url) {
        try {
            url = new String(Base64.decode(url, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
            String html = "\n" +
                    "<!doctype html>\n" +
                    "<html>\n" +
                    "<head>\n" +
                    "<title>解析</title>\n" +
                    "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\" />\n" +
                    "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=EmulateIE10\" />\n" +
                    "<meta name=\"renderer\" content=\"webkit|ie-comp|ie-stand\">\n" +
                    "<meta name=\"viewport\" content=\"width=device-width\">\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "<script>\n" +
                    "var apiArray=[#jxs#];\n" +
                    "var urlPs=\"#url#\";\n" +
                    "var iframeHtml=\"\";\n" +
                    "for(var i=0;i<apiArray.length;i++){\n" +
                    "var URL=apiArray[i]+urlPs;\n" +
                    "iframeHtml=iframeHtml+\"<iframe sandbox='allow-scripts allow-same-origin allow-forms' frameborder='0' allowfullscreen='true' webkitallowfullscreen='true' mozallowfullscreen='true' src=\"+URL+\"></iframe>\";\n" +
                    "}\n" +
                    "document.write(iframeHtml);\n" +
                    "</script>\n" +
                    "</body>\n" +
                    "</html>";

            StringBuilder jxs = new StringBuilder();
            if (flagWebJx.containsKey(flag)) {
                ArrayList<String> jxUrls = flagWebJx.get(flag);
                for (int i = 0; i < jxUrls.size(); i++) {
                    jxs.append("\"");
                    jxs.append(jxUrls.get(i));
                    jxs.append("\"");
                    if (i < jxUrls.size() - 1) {
                        jxs.append(",");
                    }
                }
            }
            html = html.replace("#url#", url).replace("#jxs#", jxs.toString());
            Object[] result = new Object[3];
            result[0] = 200;
            result[1] = "text/html; charset=\"UTF-8\"";
            ByteArrayInputStream baos = new ByteArrayInputStream(html.toString().getBytes("UTF-8"));
            result[2] = baos;
            return result;
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }
}
