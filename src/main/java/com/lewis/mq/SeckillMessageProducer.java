package com.lewis.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 秒杀消息生产者
 * 将秒杀请求写入消息队列,实现流量削峰
 */
@Component
public class SeckillMessageProducer {

    private static final Logger logger = LoggerFactory.getLogger(SeckillMessageProducer.class);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送秒杀消息
     *
     * @param seckillId  秒杀商品ID
     * @param userId     用户ID
     * @param md5        秒杀地址MD5
     */
    public void sendSeckillMessage(Long seckillId, Long userId, String md5) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("seckillId", seckillId);
            message.put("userId", userId);
            message.put("md5", md5);
            message.put("timestamp", System.currentTimeMillis());

            rabbitTemplate.convertAndSend(
                RabbitMQConfig.SECKILL_EXCHANGE,
                RabbitMQConfig.SECKILL_ROUTING_KEY,
                message
            );
            
            logger.info("Seckill message sent: seckillId={}, userId={}", seckillId, userId);
            
        } catch (Exception e) {
            logger.error("Send seckill message failed", e);
            throw new RuntimeException("秒杀请求发送失败", e);
        }
    }
}
