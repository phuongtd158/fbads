package com.fbads.config;

import com.fbads.auth.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Kiểm tra lúc khởi động: mở ra mạng mà không có mật khẩu thì từ chối chạy; báo nơi phục vụ giao diện. */
@Component
@Order(100) // sau khi nhập dữ liệu cũ (mật khẩu có thể nằm trong dữ liệu đó)
public class StartupChecks implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);

    private final AuthService auth;
    private final AppProperties props;
    private final String host;
    private final int port;

    public StartupChecks(AuthService auth, AppProperties props, @Value("${server.address:127.0.0.1}") String host, @Value("${server.port:3000}") int port) {
        this.auth = auth;
        this.props = props;
        this.host = host;
        this.port = port;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!List.of("127.0.0.1", "localhost", "::1").contains(host) && !auth.enabled())
            throw new IllegalStateException("HOST mở ra mạng nhưng chưa có mật khẩu. Hãy đặt biến môi trường APP_PASSWORD (tối thiểu 8 ký tự) rồi chạy lại.");
        if (!Files.exists(Path.of(props.publicDir(), "index.html")))
            log.warn("Chưa có bản build giao diện ở {}. Chạy: cd web && npm install && npm run build (hoặc đặt PUBLIC_DIR).", Path.of(props.publicDir()).toAbsolutePath());
        log.info("Facebook Ads Auto Tool đang chạy: http://{}:{}  | Đăng nhập: {}", host.equals("0.0.0.0") ? "localhost" : host, port, auth.enabled() ? "BẬT" : "tắt (chỉ dùng trên máy này)");
    }
}
