package com.opspulse.statuspage.service;

import com.opspulse.statuspage.dto.PublicStatusResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis-backed short-TTL response cache for public status pages.
 * Degrades gracefully to database lookups if Redis is temporarily unavailable.
 */
@Service
public class PublicStatusCacheService {

    private static final Logger log = LoggerFactory.getLogger(PublicStatusCacheService.class);
    private static final String KEY_PREFIX = "opspulse:public-status:v1:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long cacheTtlSeconds;

    public PublicStatusCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.public-status.cache-ttl-seconds:30}") long cacheTtlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public Optional<PublicStatusResponse> get(String slug) {
        if (cacheTtlSeconds <= 0 || slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(cacheKey(slug));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, PublicStatusResponse.class));
        } catch (RuntimeException ex) {
            log.warn("Failed to read public status cache for slug '{}': {}", slug, ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(String slug, PublicStatusResponse response) {
        if (cacheTtlSeconds <= 0 || slug == null || slug.isBlank() || response == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(cacheKey(slug), json, Duration.ofSeconds(cacheTtlSeconds));
        } catch (RuntimeException ex) {
            log.warn("Failed to write public status cache for slug '{}': {}", slug, ex.getMessage());
        }
    }

    public void evict(String slug) {
        if (slug == null || slug.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(cacheKey(slug));
        } catch (RuntimeException ex) {
            log.warn("Failed to evict public status cache for slug '{}': {}", slug, ex.getMessage());
        }
    }

    private String cacheKey(String slug) {
        return KEY_PREFIX + slug;
    }
}
