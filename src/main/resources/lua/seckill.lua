-- 秒杀库存原子扣减 Lua 脚本
-- KEYS[1]: 库存 key (seckill:stock:{seckillId})
-- KEYS[2]: 销量 key (seckill:sold:{seckillId})
-- ARGV[1]: 扣减数量

local stockKey = KEYS[1]
local soldKey = KEYS[2]
local count = tonumber(ARGV[1])

-- 获取当前库存
local stock = redis.call('get', stockKey)

-- 检查库存是否充足
if not stock then
    return -1  -- 库存key不存在
end

stock = tonumber(stock)
if stock < count then
    return 0  -- 库存不足
end

-- 原子扣减库存
if stock == count then
    -- 售罄，删除key
    redis.call('del', stockKey)
else
    -- 扣减库存
    redis.call('decrby', stockKey, count)
end

-- 增加销量
redis.call('incrby', soldKey, count)

return 1  -- 扣减成功
