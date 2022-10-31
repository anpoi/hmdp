package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "lock:";

    public SimpleRedisLock(String name, StringRedisTemplate redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程表示
        long id = Thread.currentThread().getId();

        //获取锁
        Boolean success = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name, id+"", timeoutSec, TimeUnit.SECONDS);

        //自动拆箱避免空指针异常
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        //释放锁完成
        redisTemplate.delete(KEY_PREFIX+name);
    }
}
