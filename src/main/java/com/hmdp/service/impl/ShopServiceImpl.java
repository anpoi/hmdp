package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
       //缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class, this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑缓存解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,LOCK_SHOP_KEY,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if(shop == null){
            return Result.fail("店铺不存在！");
        }

        return Result.ok(shop);

    }


    //缓存击穿（互斥锁）流程
    public Shop queryWithMutex(Long id){
        //1 从redis查询商铺缓存
        String shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        //2 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);

        }

        //判断命中是否是空值
        if (shopJson != null) {
            //返回一个错误信息
            return null;
        }

        //实现缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);

            //判断是否获取成功
            if(!isLock){
                //失败 则失眠并重试
                Thread.sleep(1000);
                return queryWithMutex(id);

            }

            //成功 根据id查询数据库
            shop = getById(id);

            //5 不存在，返回错误
            if(shop == null){
                //将空值写入redis
                redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);

                return null;
            }

            //6 存在，写入redis
            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unlock(lockKey);
        }


        //7 返回
        return shop;
    }


    //缓存穿透
    public Shop queryWithPassThrough(Long id){
        //1 从redis查询商铺缓存
        String shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        //2 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);

        }

        //判断命中是否是空值
        if (shopJson != null) {
            //返回一个错误信息
            return null;
        }

        //4 不存在，根据id查询数据库
        Shop shop = getById(id);

        //5 不存在，返回错误
        if(shop == null){
            //将空值写入redis
            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);

            return null;
        }

        //6 存在，写入redis
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //7 返回
        return shop;
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

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期实现流程
    public Shop queryWithLogicalExpire(Long id){
        //1 从redis查询商铺缓存
        String shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        //2 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3 存在，直接返回
            return null;

        }
        //4 命中 需要先把json反序列化对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1 未过期，直接返回店铺信息
            return shop;
        }

        //5.2 已过期，需要缓存重建
        //6 缓存重建
        //6.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        //6.2 判断是否获取锁成功
        if (isLock) {
            //注意：获取锁成功应该再次检查redis缓存是否过期，做DoubleCheck，如果存在则无需重建缓存
            //6.3 成功，开启线程实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    this.saveShop2Redis(id,30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }



        //6.4 失败，返回过期的商铺信息

        //7 返回
        return shop;
    }


    //设计逻辑过期时间
    public void saveShop2Redis(Long id,Long expireSeconds){
        //1 查询店铺数据
        Shop shop = getById(id);

        //2 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //3 写入redis
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1 更新数据库
        updateById(shop);

        //2 删除缓存
        redisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
