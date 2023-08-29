--1 参数列表
local voucherId = ARGV[1] --优惠卷ID

local userId = ARGV[2] -- 用户ID

-- 2 redis的key

local stockKey = 'seckill:stock:' .. voucherId -- 保存库存的key

local OrderKey = 'seckill:order:' .. voucherId -- 保存购买用户id的key
-- 3.判断库存是否充足
if(tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end
 -- 4.判断用户是否重复下单
if(redis.call('sismember', OrderKey, userId) == 1) then
    return 2
end
--扣减库存
redis.call('incrby', stockKey,-1)

redis.call('sadd',OrderKey, userId)
return 0