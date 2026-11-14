package com.opspulse.common.ratelimit;

import com.opspulse.common.exception.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis-backed fixed-window rate limiter applied to public status API endpoints.
 * Uses an atomic Lua script (INCR + EXPIRE) keyed by client IP address.
 */
@Component
public class PublicRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PublicRateLimitFilter.class);
    private static final String PUBLIC_PATH_PREFIX = "/api/v1/public/";
    private static final String KEY_PREFIX = "opspulse:ratelimit:public:";
    private static final String WINDOW_SECONDS = "60";

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long rateLimitPerMinute;

    public PublicRateLimitFilter(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.public-status.rate-limit-per-minute:60}") long rateLimitPerMinute
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(PUBLIC_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (rateLimitPerMinute <= 0) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        String key = KEY_PREFIX + clientIp;

        Long count = null;
        try {
            count = redisTemplate.execute(INCREMENT_SCRIPT, List.of(key), WINDOW_SECONDS);
        } catch (RuntimeException ex) {
            log.warn("Rate limiter failed for IP '{}', allowing request through: {}", clientIp, ex.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        if (count == null) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimitPerMinute));

        if (count > rateLimitPerMinute) {
            response.setHeader("X-RateLimit-Remaining", "0");
            response.setHeader(HttpHeaders.RETRY_AFTER, WINDOW_SECONDS);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());

            ApiError error = ApiError.of(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded. Please try again later.",
                    request.getRequestURI()
            );
            response.getWriter().write(objectMapper.writeValueAsString(error));
            return;
        }

        long remaining = Math.max(0L, rateLimitPerMinute - count);
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr : "unknown";
    }
}
