package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //阻塞队列：当一个线程尝试从这个队列中获取元素的时候，如果没有元素，这个队列就会被阻塞，直到队列中存在元素
    //队列才会被唤醒。
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STRAEMS streams. order >
                    // 由于存在count可能会有多条数据，所以是一个list集合
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())

                    );

                    // 2.判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        // 2.1如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }

                    // 解析消息中的订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);

                    // 4.ACK确认 SACK stream.orders g1 id
                    redisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }
        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    redisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pendding-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }

                }
            }
        }
    }

    // 用于线程池处理的任务
    // 当初始化完毕后，就会去从对列中去拿信息
//    private class VoucherOrderHandler implements Runnable {
//
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // 1.获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 2.创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//    }
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试获取锁
        boolean isLock = lock.tryLock();
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }
        try {
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单
        long orderId = redisIdWorker.nextId("order");
        // 1 执行lua脚本
        Long result = redisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        // 2 判断结果为0
        assert result != null;
        int r = result.intValue();
        if(r != 0){
            // 2.1 不为0，代表没有购买资格
            return Result.fail(r == 1 ?"库存不足" :"不能重复下单");
        }

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 3 返回订单id
        return Result.ok(orderId);
    }

//阻塞队列
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //获取用户
//        Long userId = UserHolder.getUser().getId();
//
//        // 1 执行lua脚本
//        Long result = redisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString()
//        );
//        // 2 判断结果为0
//        assert result != null;
//        int r = result.intValue();
//        if(r != 0){
//            // 2.1 不为0，代表没有购买资格
//            return Result.fail(r == 1 ?"库存不足" :"不能重复下单");
//        }
//
//        // 2.2 为0，有购买资格，把下单信息保存在阻塞队列
//        // 2.3 创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order");
//
//        voucherOrder.setId(orderId);
//        // 2.4 用户id
//        voucherOrder.setUserId(userId);
//        // 2.5 代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //2.6 创建阻塞队列
//        orderTasks.add(voucherOrder);
//
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//
//        // 3 返回订单id
//        return Result.ok(orderId);
//    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀尚未开始！");
//        }
//        // 3.判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀已经结束！");
//        }
//        // 4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            // 库存不足
//            return Result.fail("库存不足！");
//        }
//        Long  userId = UserHolder.getUser().getId();
//
//
//
//        //通过synchronized锁将用户的id锁住，这里调用的方法是为了获取到同一个用户id
//        //由于底层是通过new的形式拼接，所以使用intern去常量池去找，找到了将引用拿过来
////        synchronized (userId.toString().intern()) {
////
////            //动态代理指的是拿到了VoucherOrderServiceImpl的代理
////            //this是非代理对象，是目标对象,没有事务功能
////            //spring事务失效的可能性之一
////            //需要拿到事务的代理对象,获取代理对象
////
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//        //问题：集群下的线程并发安全问题
//        //由于不同的服务器中，jvm的锁监视器会有多个，每个线程中都会有一个成功
//        //由此导致并行运行，从而导致线程安全问题
//        //使用分布式锁：满足分布式系统或集群模式下多进程可见并且互斥的锁
//        //基于redis的分布式锁
//
//        //创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, redisTemplate);
//
//        //通过redissonClient获取锁
//        RLock lock = redissonClient.getLock("locl:order:" + userId);
//
//
//        //获取锁
//        boolean isLock = lock.tryLock();
//
//        //判断是否获取锁成功
//        if(!isLock){
//            //获取锁失败
//            return Result.fail("不允许重复下单！");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            //释放锁
//            lock.unlock();
//        }
//
//    }

    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        //5 一人一单
        Long  userId = UserHolder.getUser().getId();

        //intern 返回字符串的规范标识
        //去字符串常量池查找是否存在当前值
        //synchronized (userId.toString().intern()) {

            //5.1 查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();

            //5.2 判断用户是否下过单
            if (count > 0) {
                //用户已经购买过了
                log.error("不允许重复下单！");
                return;
            }

            //5，扣减库存  加锁 控制版本号（库存）乐观锁控制
            //失败率提高 （更改判断条件）
            boolean success = seckillVoucherService.update()
                    .setSql("stock= stock -1") //set stock = stock -1
                    .eq("voucher_id", voucherOrder) //where id = ?
//                .eq("stock",voucher.getStock()) //and stock = ?
                    //不需要判断是否已更改 只要保证库存不会超卖也就是不小于0
                    .gt("stock", 0) //and stock > 0
                    .update();
            if (!success) {
                //扣减库存
                log.error("库存不足！！");
                return;
            }

            save(voucherOrder);
        }
}


