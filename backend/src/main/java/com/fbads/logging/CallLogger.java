package com.fbads.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Ghi log một lần gọi method: vào (tham số), ra (kết quả, thời gian) hoặc lỗi.
 * Lời gọi lồng nhau (controller → service → repository) thụt lề theo độ sâu, nhìn là thấy cây gọi.
 * Logger theo tầng: fbads.calls.controller, fbads.calls.service, fbads.calls.engine, fbads.calls.repository.
 *
 * Không lộ bí mật: tham số hay trường JSON có tên chứa password/token/secret (vd. accessToken, telegramToken,
 * appSecret, passwordHash), hoặc tên là pw, code (mã đăng nhập Facebook)… in ra "***". Giá trị dài quá {@value #MAX} ký tự bị cắt, danh sách chỉ in {@value #MAX_ITEMS} phần tử đầu.
 */
public class CallLogger {
    static final int MAX = 300;
    static final int MAX_ITEMS = 5;
    private static final Pattern SECRET = Pattern.compile("(?i).*(password|passwd|token|secret|credential|apikey).*|pw|pwd|pass|code|encoded");
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private final ObjectProvider<JsonMapper> json;

    public CallLogger(ObjectProvider<JsonMapper> json) { this.json = json; }

    public Object log(ProceedingJoinPoint jp, String layer, String type) throws Throwable {
        Logger log = LoggerFactory.getLogger("fbads.calls." + layer);
        if (!log.isInfoEnabled()) return jp.proceed();
        MethodSignature sig = (MethodSignature) jp.getSignature();
        String name = type + "." + sig.getName();
        int[] depth = DEPTH.get();
        String indent = "  ".repeat(depth[0]);
        log.info("{}→ {}({})", indent, name, args(sig.getParameterNames(), jp.getArgs()));
        depth[0]++;
        long start = System.nanoTime();
        try {
            Object result = jp.proceed();
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (sig.getReturnType() == void.class) log.info("{}← {} {} ms", indent, name, ms);
            else log.info("{}← {} {} ms: {}", indent, name, ms, render(null, result));
            return result;
        } catch (Throwable t) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("{}✗ {} {} ms: {}: {}", indent, name, ms, t.getClass().getSimpleName(), cut(String.valueOf(t.getMessage())));
            throw t;
        } finally {
            if (--depth[0] == 0) DEPTH.remove();
        }
    }

    String args(String[] names, Object[] values) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            String n = names != null && i < names.length ? names[i] : "arg" + i;
            parts.add(n + "=" + render(n, values[i]));
        }
        return String.join(", ", parts);
    }

    /** Một giá trị thành chuỗi ngắn gọn để đọc log */
    String render(String name, Object v) {
        if (v == null) return "null";
        if (v instanceof CharSequence s) return name != null && SECRET.matcher(name).matches() ? "***" : cut("\"" + s + "\"");
        if (v instanceof Number || v instanceof Boolean || v instanceof Enum<?>) return v.toString();
        if (v instanceof Optional<?> o) return o.map(x -> "Optional[" + render(name, x) + "]").orElse("Optional.empty");
        Class<?> c = v.getClass();
        if (c.isSynthetic() || c.getName().contains("$$Lambda")) return "λ";
        if (v instanceof Collection<?> list) {
            List<String> items = list.stream().limit(MAX_ITEMS).map(x -> render(name, x)).toList();
            String more = list.size() > MAX_ITEMS ? ", … (" + list.size() + " phần tử)" : "";
            return cut("[" + String.join(", ", items) + more + "]");
        }
        String pkg = c.getPackageName();
        if (v instanceof byte[] b) return "byte[" + b.length + "]";
        if (pkg.startsWith("jakarta.") || pkg.startsWith("org.springframework.") || pkg.startsWith("org.apache.")) return c.getSimpleName();
        try {
            JsonNode tree = json.getObject().valueToTree(v);
            mask(tree);
            return cut(tree.toString());
        } catch (RuntimeException e) {
            return cut(String.valueOf(v));
        }
    }

    /** Che các trường bí mật trong JSON (cả lồng bên trong) */
    static void mask(JsonNode node) {
        if (node instanceof ObjectNode o) {
            for (String field : new ArrayList<>(o.propertyNames())) {
                JsonNode child = o.get(field);
                if (SECRET.matcher(field).matches() && child.isValueNode() && !child.isNull()) o.put(field, "***");
                else mask(child);
            }
        } else if (node instanceof ArrayNode a) {
            for (JsonNode child : a) mask(child);
        }
    }

    static String cut(String s) {
        return s.length() <= MAX ? s : s.substring(0, MAX) + "…(+" + (s.length() - MAX) + ")";
    }
}
