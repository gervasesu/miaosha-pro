package com.lewis.mq;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 消息队列配置
 * 用于秒杀流量削峰
 */
@Configuration
public class RabbitMQConfig {

    // 秒杀订单队列
    public static final String SECKILL_QUEUE = "seckill.order.queue";
    
    // 秒杀交换机
    public static final String SECKILL_EXCHANGE = "seckill.exchange";
    
    // 秒杀路由键
    public static final String SECKILL_ROUTING_KEY = "seckill.order";

    // 死信队列(失败消息)
    public static final String SECKILL_DLQ = "seckill.order.dlq";

    /**
     * 秒杀队列
     */
    @Bean
    public Queue seckillQueue() {
        return QueueBuilder.durable(SECKILL_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", SECKILL_DLQ)
                .build();
    }

    /**
     * 死信队列
     */
    @Bean
    public Queue seckillDLQ() {
        return QueueBuilder.durable(SECKILL_DLQ).build();
    }

    /**
     * 秒杀交换机
     */
    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE);
    }

    /**
     * 绑定队列到交换机
     */
    @Bean
    public Binding seckillBinding(Queue seckillQueue, DirectExchange seckillExchange) {
        return BindingBuilder.bind(seckillQueue)
                .to(seckillExchange)
                .with(SECKILL_ROUTING_KEY);
    }

    /**
     * 消息转换器
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }
}
