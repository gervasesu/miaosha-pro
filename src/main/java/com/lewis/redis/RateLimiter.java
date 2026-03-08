package com.lewis.redis;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Redis 限流器
 * 使用滑动窗口算法实现
 */
@Component
public class RateLimiter {

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 限流 Lua 脚本
     */
    private static final String RATE_LIMIT_SCRIPT = 
        "local key = KEYS[1] " +
        "local limit = tonumber(ARGV[1]) " +
        "local window = tonumber(ARGV[2]) " +
        "local current = redis.call('get', key) " +
        "if current == false then " +
        "    redis.call('setex', key, window, '1') " +
        "    return 1 " +
        "end " +
        "if tonumber(current) >= limit then " +
        "    return 0 " +
        "end " +
        "redis.call('incr', key) " +
        "return 1";

    /**
     * 判断是否允许访问
     *
     * @param actionId 操作ID (如: seckill:1001)
     * @param userId   用户ID
     * @param limit    限制次数
     * @param window   时间窗口(秒)
     * @return true=允许, false=拒绝
     */
    public boolean isAllowed(String actionId, String userId, int limit, int window) {
        String key = "rate_limit:" + actionId + ":" + userId;
        
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(RATE_LIMIT_SCRIPT);
        script.setResultType(Long.class);
        
        Long result = redisTemplate.execute(
            script, 
            Collections.singletonList(key),
            String.valueOf(limit),
            String.valueOf(window)
        );
        
        return result != null && result == 1L;
    }

    /**
     * 简单限流: 每分钟100次
     */
    public boolean isAllowedSimple(String actionId, String userId) {
        return isAllowed(actionId, userId, 100, 60);
    }

    /**
     * 秒杀限流: 每秒10次
     */
    public boolean isAllowedSeckill(String userId) {
        return isAllowed("seckill", userId, 10, 1);
    }
}
