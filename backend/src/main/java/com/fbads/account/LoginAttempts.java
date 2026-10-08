package com.fbads.account;

import com.fbads.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Giới hạn thử sai theo IP: 5 lần sai → khoá 15 phút. Đếm trong Redis nên khởi động lại không mất, chạy nhiều bản vẫn đếm chung:
 *  - fbads:login:fail:{ip}  INCR mỗi lần sai, tự hết hạn sau 15 phút không sai thêm
 *  - fbads:login:lock:{ip}  có khoá này = đang bị chặn; TTL của khoá = thời gian còn phải chờ
 * Sau proxy chỉ tin đúng tiêu đề IP của nền tảng đang chạy (Render: cf-connecting-ip), vì nơi khác người dùng có thể tự gửi tiêu đề này.
 */
@Service
public class LoginAttempts {
    static final int MAX_FAILS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final AppProperties props;
    private final StringRedisTemplate redis;

    public LoginAttempts(AppProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
    }

    private static String failKey(String ip) { return "fbads:login:fail:" + ip; }

    private static String lockKey(String ip) { return "fbads:login:lock:" + ip; }

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
                // phần tử CUỐI do proxy gần nhất thêm
                for (int i = parts.length - 1; i >= 0; i--) if (!parts[i].isBlank()) return parts[i].trim();
            }
        }
        return req.getRemoteAddr();
    }

    public int lockedMinutes(HttpServletRequest req) {
        Long ms = redis.getExpire(lockKey(ipOf(req)), TimeUnit.MILLISECONDS);
        return ms != null && ms > 0 ? (int) Math.ceil(ms / 60000.0) : 0;
    }

    public void recordFail(HttpServletRequest req) {
        String ip = ipOf(req);
        Long n = redis.opsForValue().increment(failKey(ip));
        redis.expire(failKey(ip), WINDOW);
        if (n != null && n >= MAX_FAILS) {
            redis.opsForValue().set(lockKey(ip), "1", WINDOW);
            redis.delete(failKey(ip));
        }
    }

    public void clear(HttpServletRequest req) {
        String ip = ipOf(req);
        redis.delete(List.of(failKey(ip), lockKey(ip)));
    }
}
