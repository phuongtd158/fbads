package com.fbads;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Máy chủ giả thay graph.facebook.com trong test (FacebookService.setGraphBase).
 * Mỗi lời gọi: lấy bước tiếp theo trong `script` nếu có ("hang" = treo, "neterr" = cắt kết nối, "500" = Facebook lỗi,
 * "rate" = bị giới hạn số lần gọi), còn lại trả lời bằng `handler` (mặc định: một tài khoản quảng cáo đang hoạt động).
 */
final class GraphStub implements AutoCloseable {
    /** Một lời gọi: path bỏ phần phiên bản (vd "act_1/ads"), params = query (GET) hoặc body (POST) */
    record Req(String method, String path, Map<String, String> params) {}

    record Res(int status, String json) {
        static Res ok(String json) { return new Res(200, json); }
    }

    private final HttpServer server;
    final List<Req> calls = new CopyOnWriteArrayList<>();
    final Deque<String> script = new ArrayDeque<>();
    volatile Function<Req, Res> handler = r -> Res.ok("{\"id\":\"1\",\"name\":\"Tài khoản\",\"currency\":\"VND\",\"account_status\":1}");

    GraphStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange ex) throws IOException {
        String raw = ex.getRequestURI().getRawQuery();
        if (!"GET".equals(ex.getRequestMethod())) raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> params = new LinkedHashMap<>();
        if (raw != null && !raw.isEmpty())
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                params.put(URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8), i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        String path = ex.getRequestURI().getPath().replaceFirst("^/v\\d+\\.\\d+/", "");
        Req req = new Req(ex.getRequestMethod(), path, params);
        calls.add(req);
        String step;
        synchronized (script) { step = script.pollFirst(); }
        Res res;
        switch (step == null ? "" : step) {
            case "hang" -> {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                ex.close();
                return;
            }
            case "neterr" -> { ex.close(); return; }
            case "500" -> res = new Res(500, "{\"error\":{\"code\":2,\"message\":\"Service temporarily unavailable\"}}");
            case "rate" -> res = new Res(400, "{\"error\":{\"code\":17,\"message\":\"User request limit reached\"}}");
            default -> res = handler.apply(req);
        }
        byte[] out = res.json().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(res.status(), out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    void script(String... steps) { synchronized (script) { script.clear(); script.addAll(List.of(steps)); } }

    @Override
    public void close() { server.stop(0); }
}
