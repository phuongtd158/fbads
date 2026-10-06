package com.fbads.config;

import com.fbads.dto.FbSnapshots;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

/**
 * Spring Cache trên Redis. Mỗi cache lưu JSON của một kiểu cố định (dễ đọc bằng redis-cli, không cần lưu tên class):
 *  - fb-objects  : danh sách camp/nhóm QC + số liệu hôm nay (FacebookObjects.listObjects)
 *  - fb-ranges   : số liệu theo khoảng ngày (FacebookInsights.rangeData)
 *  - fb-accounts : tài khoản quảng cáo của một token (@Cacheable trên FacebookAuth.listAccounts)
 * Giữ 1 ngày: FacebookObjects / FacebookInsights tự quyết khi nào số đã cũ (2–5 phút); bản cũ hơn vẫn dùng được
 * khi Facebook đang chặn số lần gọi.
 */
@Configuration
@EnableCaching
public class CacheConfig {
    public static final String OBJECTS = "fb-objects", RANGES = "fb-ranges", ACCOUNTS = "fb-accounts";

    private static <T> RedisCacheConfiguration json(JsonMapper mapper, Class<T> type, Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("fbads:cache:")
                .disableCachingNullValues()
                .entryTtl(ttl)
                .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(mapper, type)));
    }

    @Bean
    RedisCacheManagerBuilderCustomizer fbCaches(JsonMapper mapper) {
        return b -> b
                .withCacheConfiguration(OBJECTS, json(mapper, FbSnapshots.Objects.class, Duration.ofDays(1)))
                .withCacheConfiguration(RANGES, json(mapper, FbSnapshots.Range.class, Duration.ofDays(1)))
                .withCacheConfiguration(ACCOUNTS, json(mapper, Map.class, Duration.ofMinutes(5)));
    }
}
