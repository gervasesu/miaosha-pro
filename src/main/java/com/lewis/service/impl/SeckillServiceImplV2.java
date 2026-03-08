package com.lewis.service.impl;

import com.lewis.dao.SeckillDao;
import com.lewis.dao.SuccessKilledDao;
import com.lewis.dto.Exposer;
import com.lewis.dto.SeckillExecution;
import com.lewis.dto.SeckillResult;
import com.lewis.entity.Seckill;
import com.lewis.entity.SuccessKilled;
import com.lewis.enums.SeckillStatEnum;
import com.lewis.exception.RepeatKillException;
import com.lewis.exception.SeckillCloseException;
import com.lewis.exception.SeckillException;
import com.lewis.redis.CacheHelper;
import com.lewis.redis.RateLimiter;
import com.lewis.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 优化版秒杀服务
 * 解决: 超卖、限流、缓存击穿、缓存穿透等问题
 */
@Service
public class SeckillServiceImplV2 implements SeckillService {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private SeckillDao seckillDao;

    @Autowired
    private SuccessKilledDao successKilledDao;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private CacheHelper cacheHelper;

    // 混淆字符串
    private final String salt = "aksehiucka24sf*&%&^^#^%$";

    // Lua 脚本
    private static final String SECKILL_LUA_SCRIPT = 
        "local stockKey = KEYS[1] " ..
        "local soldKey = KEYS[2] " ..
        "local count = tonumber(ARGV[1]) " ..
        "local stock = redis.call('get', stockKey) " ..
        "if not stock then return -1 end " ..
        "stock = tonumber(stock) " ..
        "if stock < count then return 0 end " ..
        "if stock == count then " ..
        "    redis.call('del', stockKey) " ..
        "else " ..
        "    redis.call('decrby', stockKey, count) " ..
        "end " ..
        "redis.call('incrby', soldKey, count) " ..
        "return 1";

    @Override
    public List<Seckill> getSeckillList() {
        return seckillDao.queryAll(0, 4);
    }

    @Override
    public Seckill getById(long seckillId) {
        return seckillDao.queryById(seckillId);
    }

    @Override
    public Exposer exportSeckillUrl(long seckillId) {
        // 1. 限流检查
        String ipKey = "ip:seckill:" + seckillId;
        if (!rateLimiter.isAllowed("seckill", ipKey, 100, 60)) {
            return new Exposer(false, seckillId, -1, 0, 0, "访问过于频繁,请稍后重试");
        }

        // 2. 使用缓存助手获取数据(解决击穿、穿透)
        String cacheKey = "seckill:" + seckillId;
        Seckill seckill = cacheHelper.getCache(cacheKey, 3600, () -> {
            return seckillDao.queryById(seckillId);
        });

        if (seckill == null) {
            return new Exposer(false, seckillId);
        }

        // 3. 时间校验
        Date now = new Date();
        if (now.getTime() < seckill.getStartTime().getTime() || 
            now.getTime() > seckill.getEndTime().getTime()) {
            return new Exposer(false, seckillId, now.getTime(), 
                seckill.getStartTime().getTime(), seckill.getEndTime().getTime());
        }

        // 4. 返回加密的秒杀地址
        String md5 = getMD5(seckillId);
        return new Exposer(true, md5, seckillId);
    }

    @Override
    public SeckillExecution executeSeckill(long seckillId, long userPhone, String md5) 
            throws SeckillException, RepeatKillException, SeckillCloseException {

        if (md5 == null || !md5.equals(getMD5(seckillId))) {
            throw new SeckillException("秒杀数据被篡改");
        }

        // 1. 限流检查
        if (!rateLimiter.isAllowedSeckill(String.valueOf(userPhone))) {
            throw new SeckillException("请求过于频繁,请稍后重试");
        }

        Date now = new Date();

        try {
            // 2. 使用 Lua 脚本原子扣减库存(解决超卖)
            String stockKey = "seckill:stock:" + seckillId;
            String soldKey = "seckill:sold:" + seckillId;
            
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptText(SECKILL_LUA_SCRIPT);
            script.setResultType(Long.class);
            
            Long result = redisTemplate.execute(
                script,
                Collections.singletonList(stockKey, soldKey),
                "1"  // 扣减1个
            );

            if (result == null || result == 0L) {
                // 库存不足
                throw new SeckillCloseException("秒杀已结束");
            }
            if (result == -1L) {
                throw new SeckillException("秒杀商品不存在");
            }

            // 3. 插入购买明细(唯一索引防止重复)
            int insertCount = successKilledDao.insertSuccessKilled(seckillId, userPhone);
            if (insertCount <= 0) {
                throw new RepeatKillException("重复秒杀");
            }

            // 4. 查询秒杀结果
            SuccessKilled successKilled = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
            return new SeckillExecution(seckillId, SeckillStatEnum.SUCCESS, successKilled);

        } catch (RepeatKillException e1) {
            throw e1;
        } catch (SeckillCloseException e2) {
            throw e2;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new SeckillException("秒杀系统异常:" + e.getMessage());
        }
    }

    @Override
    public SeckillExecution executeSeckillProcedure(long seckillId, long userPhone, String md5) {
        // 优化版不使用存储过程,直接用Lua脚本
        return executeSeckill(seckillId, userPhone, md5);
    }

    /**
     * 预热秒杀商品库存到Redis
     * 在秒杀开始前调用
     */
    public void warmupSeckillStock(long seckillId) {
        Seckill seckill = seckillDao.queryById(seckillId);
        if (seckill != null) {
            String stockKey = "seckill:stock:" + seckillId;
            redisTemplate.opsForValue().set(stockKey, String.valueOf(seckill.getNumber()));
            logger.info("Warmup seckill stock: seckillId={}, stock={}", seckillId, seckill.getNumber());
        }
    }

    private String getMD5(long seckillId) {
        String base = seckillId + "/" + salt;
        String md5 = org.springframework.util.DigestUtils.md5DigestAsHex(base.getBytes());
        return md5;
    }
}
