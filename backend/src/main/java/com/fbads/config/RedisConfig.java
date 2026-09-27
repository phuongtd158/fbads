package com.fbads.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis dùng cho: phiên đăng nhập (Spring Session), bộ nhớ đệm số liệu Facebook (Spring Cache, xem CacheConfig),
 * đếm đăng nhập sai (LoginAttempts), khoá chạy tự động (ShedLock) và pub/sub sự kiện cho WebSocket (LiveEvents).
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT15M")
public class RedisConfig {
    /** ShedLock lưu khoá ở khoá Redis "job-lock:fbads:<tên>" */
    @Bean
    LockProvider lockProvider(RedisConnectionFactory factory) { return new RedisLockProvider(factory, "fbads"); }

    /** Nghe các kênh pub/sub (LiveEvents đăng ký kênh của mình vào đây) */
    @Bean
    RedisMessageListenerContainer redisListeners(RedisConnectionFactory factory) {
        RedisMessageListenerContainer c = new RedisMessageListenerContainer();
        c.setConnectionFactory(factory);
        return c;
    }
}
