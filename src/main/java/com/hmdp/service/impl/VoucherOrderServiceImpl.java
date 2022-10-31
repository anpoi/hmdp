package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }
        Long  userId = UserHolder.getUser().getId();



        //通过synchronized锁将用户的id锁住，这里调用的方法是为了获取到同一个用户id
        //由于底层是通过new的形式拼接，所以使用intern去常量池去找，找到了将引用拿过来
//        synchronized (userId.toString().intern()) {
//
//            //动态代理指的是拿到了VoucherOrderServiceImpl的代理
//            //this是非代理对象，是目标对象,没有事务功能
//            //spring事务失效的可能性之一
//            //需要拿到事务的代理对象,获取代理对象
//
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }

        //问题：集群下的线程并发安全问题
        //由于不同的服务器中，jvm的锁监视器会有多个，每个线程中都会有一个成功
        //由此导致并行运行，从而导致线程安全问题
        //使用分布式锁：满足分布式系统或集群模式下多进程可见并且互斥的锁
        //基于redis的分布式锁

        //创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, redisTemplate);

        //获取锁
        boolean isLock = lock.tryLock(10);

        //判断是否获取锁成功
        if(!isLock){
            //获取锁失败
            return Result.fail("不允许重复下单！");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            //释放锁
            lock.unlock();
        }

    }

    @Transactional(rollbackFor = Exception.class)
    public  Result createVoucherOrder(long voucherId) {

        //5 一人一单
        Long  userId = UserHolder.getUser().getId();

        //intern 返回字符串的规范标识
        //去字符串常量池查找是否存在当前值
        //synchronized (userId.toString().intern()) {

            //5.1 查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

            //5.2 判断用户是否下过单
            if (count > 0) {
                //用户已经购买过了
                return Result.fail("用户已经购买过一次！");
            }

            //5，扣减库存  加锁 控制版本号（库存）乐观锁控制
            //失败率提高 （更改判断条件）
            boolean success = seckillVoucherService.update()
                    .setSql("stock= stock -1") //set stock = stock -1
                    .eq("voucher_id", voucherId) //where id = ?
//                .eq("stock",voucher.getStock()) //and stock = ?
                    //不需要判断是否已更改 只要保证库存不会超卖也就是不小于0
                    .gt("stock", 0) //and stock > 0
                    .update();
            if (!success) {
                //扣减库存
                return Result.fail("库存不足！");
            }
            //6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 6.1.订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 6.2.用户id
            voucherOrder.setUserId(userId);
            // 6.3.代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            return Result.ok(orderId);
        //}
    }
}
