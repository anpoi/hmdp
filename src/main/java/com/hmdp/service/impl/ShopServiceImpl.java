package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

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

    @Override
    public Result queryById(Long id) {
       //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if(shop == null){
            return Result.fail("店铺不存在！");
        }

        return Result.ok(shop);

    }
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


    //缓存击穿（互斥锁）
    private boolean tryLock(String key){
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        redisTemplate.delete(key);
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
