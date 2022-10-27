package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
import static com.hmdp.utils.RedisConstants.SHOUYE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public  List<ShopType> queryList() {
        //1 从redis查询商铺缓存
        String shopJson = redisTemplate.opsForValue().get(SHOUYE_SHOP_KEY);

        //2 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3 存在，直接返回
            return JSONUtil.toList(shopJson, ShopType.class);

        }

        //4 不存在，根据id查询数据库
        List<ShopType> shopTypes = list();
        //5 不存在，返回错误
        if(shopTypes == null){
            return null;
        }
        //6 存在，写入redis
        redisTemplate.opsForValue().set(RedisConstants.SHOUYE_SHOP_KEY,JSONUtil.toJsonStr(shopTypes), LOGIN_USER_TTL, TimeUnit.SECONDS);
        //7 返回
        return shopTypes;
    }
}
