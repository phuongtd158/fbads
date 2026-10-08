package com.fbads.config;

import com.fbads.account.AuthService;
import com.fbads.security.WorkspaceContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket STOMP tại /ws: trình duyệt SUBSCRIBE "/topic/ws.{id}.logs", ".objects", ".engine" của workspace đang chọn
 * (xem event/LiveEvents).
 *  - Chỉ nghe được kênh của workspace mình là thành viên (chế độ mở: chỉ workspace 1).
 *  - Chỉ nhận kết nối cùng origin (mặc định của Spring khi không khai báo origin nào khác).
 *  - Cần đăng nhập: /ws đi qua Spring Security như /api (security/SecurityConfig).
 *  - Trình duyệt chỉ được nghe, không được gửi: chặn lệnh SEND để không ai phát sự kiện giả cho người khác.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private static final Pattern TOPIC = Pattern.compile("^/topic/ws\\.(\\d{1,18})\\.(logs|objects|engine)$");

    private final AuthService auth;

    public WebSocketConfig(AuthService auth) { this.auth = auth; }

    /** Người này (null = chưa đăng nhập) có được nghe kênh này không */
    boolean canSubscribe(String destination, Principal user) {
        Matcher m = destination == null ? null : TOPIC.matcher(destination);
        if (m == null || !m.matches()) return false;
        long ws = Long.parseLong(m.group(1));
        if (auth.openMode()) return ws == WorkspaceContext.DEFAULT;
        if (user == null) return false;
        return auth.user(user.getName()).map(u -> auth.role(ws, u.getId()) != null).orElse(false);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor h = StompHeaderAccessor.wrap(message);
                if (StompCommand.SEND.equals(h.getCommand())) throw new IllegalArgumentException("Không nhận tin từ trình duyệt");
                if (StompCommand.SUBSCRIBE.equals(h.getCommand()) && !canSubscribe(h.getDestination(), h.getUser()))
                    throw new IllegalArgumentException("Kênh không hợp lệ hoặc không thuộc workspace của bạn");
                return message;
            }
        });
    }
}
