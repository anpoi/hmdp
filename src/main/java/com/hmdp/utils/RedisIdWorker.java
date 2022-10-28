package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 唯一id生成类
 */
@Component
public class RedisIdWorker {
    /**
     * 2022-01-01 0:0:0
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final int COUNT_BITS = 32;

    @Autowired
    private StringRedisTemplate redisTemplate;

    public long nextId(String keyPrefix){
        //1 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        //2 生成序列号
        //2.1 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        //2.2 自增长
        Long count = redisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);


        //3 拼接并返回

        return timeStamp << COUNT_BITS | count;
    }

}
