package com.fbads;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Client HTTP nhỏ cho test tích hợp: gửi JSON như giao diện (Origin + Content-Type), giữ cookie phiên. */
final class Api {
    static final JsonMapper JSON = JsonMapper.builder().build();

    record Res(int status, JsonNode body) {}

    private final String base;
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    Api(int port) { this.base = "http://localhost:" + port; }

    Res get(String path) { return send(HttpRequest.newBuilder(URI.create(base + path)).GET()); }

    Res post(String path, Object body) { return send(json(path, body).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))); }

    Res delete(String path) { return send(json(path, null).DELETE()); }

    private HttpRequest.Builder json(String path, Object body) {
        return HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json").header("Origin", base);
    }

    private Res send(HttpRequest.Builder b) {
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String text = r.body();
            return new Res(r.statusCode(), text == null || text.isBlank() || !text.trim().startsWith("{") && !text.trim().startsWith("[") ? null : JSON.readTree(text));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
