package com.fbads.auth;

import com.fbads.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giới hạn thử sai theo IP: 5 lần sai → khoá 15 phút.
 * Sau proxy chỉ tin đúng tiêu đề IP của nền tảng đang chạy (Render: cf-connecting-ip), vì nơi khác người dùng có thể tự gửi tiêu đề này.
 */
@Component
public class LoginAttempts {
    private record Fail(int n, long until) {}

    private final Map<String, Fail> fails = new ConcurrentHashMap<>();
    private final AppProperties props;

    public LoginAttempts(AppProperties props) { this.props = props; }

    private String ipHeader() {
        String h = props.clientIpHeader().trim().toLowerCase();
        if (!h.isEmpty()) return h;
        return props.render() ? "cf-connecting-ip" : "";
    }

    public String ipOf(HttpServletRequest req) {
        if (props.trustProxy() || props.render()) {
            String h = ipHeader(), v = h.isEmpty() ? null : req.getHeader(h);
            if (v != null && !v.isBlank()) return v.split(",")[0].trim();
            String xf = req.getHeader("x-forwarded-for");
            if (xf != null && !xf.isBlank()) {
                String[] parts = xf.split(",");
                for (int i = parts.length - 1; i >= 0; i--) if (!parts[i].isBlank()) return parts[i].trim(); // phần tử CUỐI do proxy gần nhất thêm
            }
        }
        return req.getRemoteAddr();
    }

    public int lockedMinutes(HttpServletRequest req) {
        Fail f = fails.get(ipOf(req));
        long now = System.currentTimeMillis();
        return f != null && f.until() > now ? (int) Math.ceil((f.until() - now) / 60000.0) : 0;
    }

    public void recordFail(HttpServletRequest req) {
        fails.compute(ipOf(req), (k, f) -> {
            int n = (f == null ? 0 : f.n()) + 1;
            return n >= 5 ? new Fail(0, System.currentTimeMillis() + 15 * 60_000) : new Fail(n, f == null ? 0 : f.until());
        });
    }

    public void clear(HttpServletRequest req) { fails.remove(ipOf(req)); }
}
