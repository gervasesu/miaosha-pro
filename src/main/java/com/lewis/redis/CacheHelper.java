package com.lewis.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis 缓存助手
 * 解决雪崩、击穿、穿透问题
 */
@Component
public class CacheHelper {

    private static final Logger logger = LoggerFactory.getLogger(CacheHelper.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 缓存穿透解决方案: 空值缓存
     */
    private static final String CACHE_NULL_PREFIX = "cache:null:";
    private static final long NULL_CACHE_EXPIRE = 300; // 空值缓存5分钟

    /**
     * 获取缓存,如果不存在则从数据库加载并缓存
     * 
     * @param cacheKey    缓存key
     * @param expire      过期时间(秒)
     * @param loader      数据库加载函数
     * @param <T>         返回类型
     * @return 缓存值
     */
    public <T> T getCache(String cacheKey, long expire, Supplier<T> loader) {
        try {
            // 1. 尝试从缓存获取
            String value = redisTemplate.opsForValue().get(cacheKey);
            if (value != null) {
                logger.debug("Cache hit: {}", cacheKey);
                return parseValue(value);
            }

            // 2. 检查是否是空值缓存(缓存穿透)
            String nullKey = CACHE_NULL_PREFIX + cacheKey;
            String nullValue = redisTemplate.opsForValue().get(nullKey);
            if (nullValue != null) {
                logger.debug("Null cache hit: {}", cacheKey);
                return null;
            }

            // 3. 从数据库加载
            T result = loader.get();
            if (result != null) {
                // 4. 放入缓存
                String cacheValue = serializeValue(result);
                // 随机过期时间,防止雪崩
                long randomExpire = expire + (long) (Math.random() * expire * 0.2);
                redisTemplate.opsForValue().set(cacheKey, cacheValue, randomExpire, TimeUnit.SECONDS);
                logger.debug("Cache set: {}, expire: {}s", cacheKey, expire);
            } else {
                // 5. 空值缓存,防止缓存穿透
                redisTemplate.opsForValue().set(nullKey, "1", NULL_CACHE_EXPIRE, TimeUnit.SECONDS);
                logger.debug("Null cache set: {}", cacheKey);
            }
            
            return result;
            
        } catch (Exception e) {
            logger.error("Cache error: {}", cacheKey, e);
            // 降级: 直接从数据库加载
            return loader.get();
        }
    }

    /**
     * 逻辑过期解决缓存击穿
     * 适用于热点数据
     */
    public <T> T getCacheLogicalExpire(String cacheKey, Supplier<T> loader) {
        try {
            String value = redisTemplate.opsForValue().get(cacheKey);
            if (value != null) {
                return parseValue(value);
            }
            
            // 触发异步加载(此处简化,实际可用分布式锁)
            synchronized(this) {
                value = redisTemplate.opsForValue().get(cacheKey);
                if (value != null) {
                    return parseValue(value);
                }
                
                T result = loader.get();
                if (result != null) {
                    redisTemplate.opsForValue().set(cacheKey, serializeValue(result));
                }
                return result;
            }
        } catch (Exception e) {
            logger.error("Logical expire cache error: {}", cacheKey, e);
            return loader.get();
        }
    }

    /**
     * 删除缓存
     */
    public void delete(String cacheKey) {
        try {
            redisTemplate.delete(cacheKey);
            redisTemplate.delete(CACHE_NULL_PREFIX + cacheKey);
        } catch (Exception e) {
            logger.error("Delete cache error: {}", cacheKey, e);
        }
    }

    /**
     * 序列化(简单实现,可使用Jackson)
     */
    private <T> String serializeValue(T value) {
        return value.toString();
    }

    /**
     * 反序列化
     */
    @SuppressWarnings("unchecked")
    private <T> T parseValue(String value) {
        return (T) value;
    }
}
