package com.github.tvbox.osc.bbox.util.parser;
import android.util.Base64;
import com.github.catvod.crawler.SpiderDebug;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 并发解析，直到获得第一个结果。
 *
 * 1.0.14 重构（性能+并发正确性）：
 *  - OkHttpClient 改为共享单例：连接池/TLS 会话复用，老盒子每次解析省下重复握手与线程池开销。
 *  - 线程池改为共享常驻（守护线程）：不再每次 parse 新建 5 线程又 shutdown。
 *  - futures/calls 改为方法局部：消除并发调用间互相 cancel/clear 的竞态。
 *  - 结果超时保护（15s）：原 completionService.take() 无限等待，jx 全挂起时解析线程永久阻塞。
 *  - Response 显式 close：取消/异常路径不再泄漏连接。
 *  - 只取消本批请求（按 Call 精确取消），不再 dispatcher().cancelAll() 误伤其它解析。
 */
public class JsonParallel {

    /** 整批解析的超时上限（毫秒）：防止某个 jx 挂死导致解析线程永久阻塞。 */
    private static final long PARSE_TIMEOUT_MS = 15_000L;

    /** 共享 HTTP 客户端：连接池/线程全部复用（原实现每次 parse 新建，开销大且无法复用连接）。 */
    private static final OkHttpClient client = new OkHttpClient();

    /** 共享解析线程池：守护线程，随进程退出，不再反复创建销毁。 */
    private static final ExecutorService executorService = Executors.newFixedThreadPool(5, new java.util.concurrent.ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "json-parallel");
            t.setDaemon(true);
            return t;
        }
    });

    public static JSONObject parse(LinkedHashMap<String, String> jx, String url) {
        if (jx == null || jx.isEmpty()) return new JSONObject();
        List<Future<JSONObject>> futures = new ArrayList<>(jx.size());
        // 本批请求的 Call 登记表：拿到首个有效结果后只取消本批，不影响并发中的其它解析批次
        final ConcurrentHashMap<String, Call> calls = new ConcurrentHashMap<>();
        try {
            CompletionService<JSONObject> completionService = new ExecutorCompletionService<>(executorService);

            // 遍历所有的解析配置
            for (final String jxName : jx.keySet()) {
                final String parseUrl = jx.get(jxName);
                if (parseUrl == null) continue;
                futures.add(completionService.submit(new Callable<JSONObject>() {
                    @Override
                    public JSONObject call() {
                        Response response = null;
                        try {
                            // 获取请求头，并从中取出实际url
                            HashMap<String, String> reqHeaders = JsonParallel.getReqHeader(parseUrl);
                            String realUrl = reqHeaders.remove("url");
                            if (realUrl == null) return null;
                            Headers headers = Headers.of(reqHeaders);
                            Request request = new Request.Builder()
                                    .url(realUrl + url)
                                    .headers(headers)
                                    .tag("ParseTag")
                                    .build();

                            Call call = client.newCall(request);
                            calls.put(jxName, call);
                            response = call.execute();
                            String json = response.body().string();

                            JSONObject taskResult = Utils.jsonParse(url, json);
                            taskResult.put("jxFrom", jxName);
                            return taskResult;
                        } catch (Throwable th) {
                            // 输出日志
                            return null;
                        } finally {
                            if (response != null) {
                                try { response.close(); } catch (Throwable ignored) { }
                            }
                        }
                    }
                }));
            }

            JSONObject pTaskResult = null;
            long deadline = System.currentTimeMillis() + PARSE_TIMEOUT_MS;
            for (int i = 0; i < futures.size(); ++i) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break; // 整体超时，放弃剩余结果
                Future<JSONObject> completed = completionService.poll(remain, TimeUnit.MILLISECONDS);
                if (completed == null) break; // 超时
                try {
                    JSONObject r = completed.get();
                    if (r != null) {
                        pTaskResult = r;
                        break; // 拿到首个有效结果
                    }
                } catch (Throwable th) {
                    SpiderDebug.log(th);
                }
            }

            // 收尾：取消本批未完成的请求与任务（不再使用全局 cancelAll，避免误伤并发批次）
            if (pTaskResult != null) {
                for (Call c : calls.values()) {
                    try { c.cancel(); } catch (Throwable ignored) { }
                }
            }
            for (Future<JSONObject> future : futures) {
                try { future.cancel(true); } catch (Throwable ignored) { }
            }
            if (pTaskResult != null) return pTaskResult;
        } catch (Throwable th) {
            SpiderDebug.log(th);
        }
        return new JSONObject();
    }

    /** 外部主动停止解析（切集/退出播放时调用）：取消所有进行中的解析请求。 */
    public static void cancelTasks() {
        client.dispatcher().cancelAll();
    }

    public static HashMap<String, String> getReqHeader(String url) {
        HashMap<String, String> reqHeaders = new HashMap<>();
        reqHeaders.put("url", url);
        if (url.contains("cat_ext")) {
            try {
                int start = url.indexOf("cat_ext=");
                int end = url.indexOf("&", start);
                // 修复：cat_ext 位于参数末尾时 end == -1，原实现 substring(start+8, -1) 抛异常且
                // 回退 newUrl = url.substring(0,start)+url.substring(0) 拼回整个 url，导致该 jx 请求错乱
                String ext;
                String newUrl;
                if (end == -1) {
                    ext = url.substring(start + 8);
                    newUrl = url.substring(0, start);
                } else {
                    ext = url.substring(start + 8, end);
                    newUrl = url.substring(0, start) + url.substring(end + 1);
                }
                ext = new String(Base64.decode(ext, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP));
                JSONObject jsonObject = new JSONObject(ext);
                if (jsonObject.has("header")) {
                    JSONObject headerJson = jsonObject.optJSONObject("header");
                    Iterator<String> keys = headerJson.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        reqHeaders.put(key, headerJson.optString(key, ""));
                    }
                }
                reqHeaders.put("url", newUrl);
            } catch (Throwable th) {

            }
        }
        return reqHeaders;
    }
}
