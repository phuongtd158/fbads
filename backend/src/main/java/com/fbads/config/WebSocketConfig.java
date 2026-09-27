package com.fbads.config;

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

/**
 * WebSocket STOMP tại /ws: trình duyệt SUBSCRIBE "/topic/logs", "/topic/objects", "/topic/engine" (xem service/LiveEvents).
 *  - Chỉ nhận kết nối cùng origin (mặc định của Spring khi không khai báo origin nào khác).
 *  - Cần đăng nhập: /ws đi qua Spring Security như /api (security/SecurityConfig).
 *  - Trình duyệt chỉ được nghe, không được gửi: chặn lệnh SEND để không ai phát sự kiện giả cho người khác.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

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
                if (StompCommand.SUBSCRIBE.equals(h.getCommand()) && (h.getDestination() == null || !h.getDestination().startsWith("/topic/")))
                    throw new IllegalArgumentException("Kênh không hợp lệ");
                return message;
            }
        });
    }
}
