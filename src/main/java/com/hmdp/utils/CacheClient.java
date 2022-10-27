package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 自定义redis工具类
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate redisTemplate;

    public CacheClient(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }


    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }


    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //穿透
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback
                                            ,Long time, TimeUnit timeUnit){
        String key = keyPrefix + id;
        //1 从redis查询商铺缓存
        String json = redisTemplate.opsForValue().get(key);

        //2 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3 存在，直接返回
            return JSONUtil.toBean(json, type);

        }

        //判断命中是否是空值
        if (json != null) {
            //返回一个错误信息
            return null;
        }

        //4 不存在，根据id查询数据库
        R r = dbFallback.apply(id);

        //5 不存在，返回错误
        if(r == null){
            //将空值写入redis
            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }

        //6 存在，写入redis
        this.set(key,JSONUtil.toJsonStr(r),time,timeUnit);

        //7 返回
        return r;
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期实现流程
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id , Class<R> type,String lockKey
                                        ,Function<ID,R> deFallback,Long time, TimeUnit timeUnit){
        String key = keyPrefix + id;
        //1 从redis查询商铺缓存
        String json = redisTemplate.opsForValue().get(key);

        //2 判断是否存在
        if (StrUtil.isBlank(json)) {
            //3 存在，直接返回
            return null;

        }
        //4 命中 需要先把json反序列化对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1 未过期，直接返回店铺信息
            return r;
        }

        //5.2 已过期，需要缓存重建
        //6 缓存重建
        //6.1 获取互斥锁
        String lockKeyId = lockKey + id;


        boolean isLock = tryLock(lockKeyId);

        //6.2 判断是否获取锁成功
        if (isLock) {
            //注意：获取锁成功应该再次检查redis缓存是否过期，做DoubleCheck，如果存在则无需重建缓存
            //6.3 成功，开启线程实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存,查询数据库
                    R r1 = deFallback.apply(id);

                    //写入redis

                    this.setWithLogicalExpire(key,r1,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKeyId);
                }

            });
        }
        //6.4 失败，返回过期的商铺信息
        //7 返回
        return r;
    }


    //缓存击穿（互斥锁）加锁
    private boolean tryLock(String key){
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //缓存击穿（互斥锁）释放锁
    private void unlock(String key){
        redisTemplate.delete(key);
    }
}
