package com.lewis.web;

import com.lewis.dto.Exposer;
import com.lewis.dto.SeckillExecution;
import com.lewis.dto.SeckillResult;
import com.lewis.entity.Seckill;
import com.lewis.enums.SeckillStatEnum;
import com.lewis.exception.RepeatKillException;
import com.lewis.exception.SeckillCloseException;
import com.lewis.mq.SeckillMessageProducer;
import com.lewis.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

/**
 * 优化版秒杀控制器
 * 集成限流、消息队列削峰
 */
@Controller
@RequestMapping("/seckill")
public class SeckillControllerV2 {

    private final Logger logger = LoggerFactory.getLogger(SeckillControllerV2.class);

    @Autowired
    private SeckillService seckillService;

    @Autowired
    private SeckillMessageProducer messageProducer;

    /**
     * 获取秒杀列表
     */
    @RequestMapping(value = "/list", method = RequestMethod.GET)
    public String list(Model model) {
        List<Seckill> list = seckillService.getSeckillList();
        model.addAttribute("list", list);
        return "list";
    }

    /**
     * 获取秒杀详情
     */
    @RequestMapping(value = "/{seckillId}/detail", method = RequestMethod.GET)
    public String detail(@PathVariable("seckillId") Long seckillId, Model model) {
        if (seckillId == null) {
            return "redirect:/seckill/list";
        }
        
        Seckill seckill = seckillService.getById(seckillId);
        if (seckill == null) {
            return "forward:/seckill/list";
        }
        
        model.addAttribute("seckill", seckill);
        return "detail";
    }

    /**
     * 暴露秒杀地址
     */
    @RequestMapping(value = "/{seckillId}/exposer", 
                    method = RequestMethod.GET,
                    produces = {"application/json;charset=UTF-8"})
    @ResponseBody
    public SeckillResult<Exposer> exposer(@PathVariable("seckillId") Long seckillId) {
        try {
            Exposer exposer = seckillService.exportSeckillUrl(seckillId);
            return new SeckillResult<>(true, exposer);
        } catch (Exception e) {
            logger.error("exposer error", e);
            return new SeckillResult<>(false, e.getMessage());
        }
    }

    /**
     * 执行秒杀 - 优化版(写入消息队列)
     */
    @RequestMapping(value = "/{seckillId}/{md5}/execution",
                    method = RequestMethod.POST,
                    produces = {"application/json;charset=UTF-8"})
    @ResponseBody
    public SeckillResult<SeckillExecution> executeOptimized(
            @PathVariable("seckillId") Long seckillId,
            @PathVariable("md5") String md5,
            @CookieValue(value = "userPhone", required = false) Long userPhone) {

        if (userPhone == null) {
            return new SeckillResult<>(false, "未注册");
        }

        try {
            // 优化: 写入消息队列,实现异步处理和削峰
            messageProducer.sendSeckillMessage(seckillId, userPhone, md5);
            
            // 立即返回"排队中"状态
            return new SeckillResult<>(true, 
                new SeckillExecution(seckillId, SeckillStatEnum.QUEUING, null));
                
        } catch (Exception e) {
            logger.error("execute error", e);
            return new SeckillResult<>(false, e.getMessage());
        }
    }

    /**
     * 查询秒杀结果(轮询)
     */
    @RequestMapping(value = "/{seckillId}/result", method = RequestMethod.GET)
    @ResponseBody
    public SeckillResult<SeckillExecution> result(
            @PathVariable("seckillId") Long seckillId,
            @CookieValue(value = "userPhone", required = false) Long userPhone) {

        if (userPhone == null) {
            return new SeckillResult<>(false, "未注册");
        }

        try {
            SeckillExecution execution = seckillService.getSeckillResult(seckillId, userPhone);
            return new SeckillResult<>(true, execution);
        } catch (Exception e) {
            logger.error("result query error", e);
            return new SeckillResult<>(false, e.getMessage());
        }
    }

    /**
     * 获取系统时间
     */
    @RequestMapping(value = "/time/now", method = RequestMethod.GET)
    @ResponseBody
    public SeckillResult<Long> time() {
        return new SeckillResult<>(true, new Date().getTime());
    }
}
