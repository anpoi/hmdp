package com.hmdp.utils;


import cn.hutool.core.lang.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 基于分布式锁的实现思路：
 *  利用setnx ex获取锁，并且设置过期时间，保证线程标识
 *  释放锁时先判断线程标识是否与自己一致，一致则删除锁
 *
 *  特性：
 *  利用setnx满足互斥性
 *  利用setex保证故障时锁依然能释放，避免死锁，提高安全性
 *  利用redis集群保证高可用和高并发特性
 */

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "lock:";

    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    public SimpleRedisLock(String name, StringRedisTemplate redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String id = ID_PREFIX+Thread.currentThread().getId();

        //获取锁
        Boolean success = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name, id, timeoutSec, TimeUnit.SECONDS);

        //自动拆箱避免空指针异常
        return Boolean.TRUE.equals(success);
    }

    //之前的代码判断和修改是有两步操作
    //极端情况下可能会照成误删操作
    //将判断和修改合并成异步，通过lua脚本实现
    @Override
    public void unlock() {
        //调用lua脚本 保证原子性
        redisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX+Thread.currentThread().getId());

    }
//    @Override
//    public void unlock() {
//        //获取线程标识
//        String id = ID_PREFIX+Thread.currentThread().getId();
//
//        //获取锁中的标识
//        String lockId = redisTemplate.opsForValue().get(KEY_PREFIX+name);
//
//        //判断是否一致
//        if(id.equals(lockId)){
//            //释放锁完成
//            redisTemplate.delete(KEY_PREFIX+name);
//        }
//
//    }
}
