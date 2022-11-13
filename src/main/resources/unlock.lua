---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by 86185.
--- DateTime: 2022/11/1 15:48
---
-- 获取锁中的线程标识 get key
local id = redis.call('get',KEYS[1])

-- 比较线程标识与锁中的标识是否一致
if(id == ARGV[1]) then
    -- 释放锁 del key
    return redis.call('del',KEYS[1])
end
return 0
