package com.fbads;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Máy chủ giả thay api.telegram.org trong test (TelegramChannel.setApiBase): ghi lại mọi tin nhận được,
 * trả mã HTTP tuỳ chỉnh (200 = gửi được, 500 = Telegram lỗi tạm thời, 400 = chat id sai).
 */
final class TelegramStub implements AutoCloseable {
    private final HttpServer server;
    /** Nội dung (text) mọi lần gọi sendMessage, kể cả lần bị trả lỗi */
    final List<String> texts = new CopyOnWriteArrayList<>();
    /** Chat ID của từng lần gọi, cùng thứ tự với texts */
    final List<String> chats = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    /** Chat ID này luôn bị trả 500 (Telegram lỗi tạm thời), các chat khác theo status */
    volatile String failChat;

    TelegramStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            JsonNode body = Api.JSON.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            texts.add(body.path("text").asString());
            String chat = body.path("chat_id").asString();
            chats.add(chat);
            int code = chat.equals(failChat) ? 500 : status;
            String res = code == 200 ? "{\"ok\":true,\"result\":{}}"
                    : "{\"ok\":false,\"error_code\":" + code + ",\"description\":\"" + (code == 400 ? "Bad Request: chat not found" : "Internal Server Error") + "\"}";
            byte[] out = res.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    long count(String text) { return texts.stream().filter(text::equals).count(); }

    @Override
    public void close() { server.stop(0); }
}
