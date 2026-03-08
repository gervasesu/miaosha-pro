package com.lewis.mq;

import com.lewis.dto.SeckillExecution;
import com.lewis.enums.SeckillStatEnum;
import com.lewis.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 秒杀消息消费者
 * 异步处理秒杀请求,实现流量削峰
 */
@Component
public class SeckillMessageConsumer {

    private static final Logger logger = LoggerFactory.getLogger(SeckillMessageConsumer.class);

    @Autowired
    private SeckillService seckillService;

    /**
     * 监听秒杀队列,处理秒杀请求
     */
    @RabbitListener(queues = RabbitMQConfig.SECKILL_QUEUE)
    public void consumeSeckillMessage(Map<String, Object> message) {
        Long seckillId = Long.valueOf(message.get("seckillId").toString());
        Long userId = Long.valueOf(message.get("userId").toString());
        String md5 = (String) message.get("md5");

        logger.info("Received seckill message: seckillId={}, userId={}", seckillId, userId);

        try {
            // 执行秒杀
            SeckillExecution execution = seckillService.executeSeckill(seckillId, userId, md5);
            
            if (SeckillStatEnum.SUCCESS.equals(execution.getState())) {
                logger.info("Seckill success: seckillId={}, userId={}", seckillId, userId);
                // TODO: 发送成功通知(短信/邮件/站内信)
            } else {
                logger.warn("Seckill failed: seckillId={}, userId={}, state={}", 
                    seckillId, userId, execution.getState());
                // TODO: 发送失败通知
            }
            
        } catch (Exception e) {
            logger.error("Seckill execution error: seckillId={}, userId={}", seckillId, userId, e);
            // 异常消息会自动进入死信队列
            throw new RuntimeException(e);
        }
    }
}
