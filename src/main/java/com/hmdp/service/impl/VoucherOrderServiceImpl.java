package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 *
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    ISeckillVoucherService iSeckillVoucherService;
    @Resource
    RedisIdWorker redisIdWorker;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 在类初始化完成后就直接开始启动线程处理阻塞队列的任务
     */
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit( () -> {
            while (true) {
                try {
                    VoucherOrder task = orderTasks.take();
                    handleVoucherOrder(task);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 处理订单请求
     * @param order 订单信息
     */
    private void handleVoucherOrder(VoucherOrder order) {
        Long id = order.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + id);
        if (!lock.tryLock()) {
            log.error("用户重复下单");
        }
        try {
            //2.使用代理对象来调用具有事务的方法
            proxy.GetcreateVoucherOrder(order);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 使用redis脚本实现优惠卷合法性判断,并将结果返回前端后异步得进行创建订单
     * @param voucherId
     * @return
     */
    //创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks =new ArrayBlockingQueue<>(1024 * 1024);
    //代理对象
    IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        // 执行lua脚本
        Long res = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                user.getId().toString()
        );
        //判断结果
        int value = res.intValue();
        if(value != 0){
            return value == 1 ? Result.fail("优惠卷库存不足") : Result.fail("不能重复下单");
        }

        //判断购买合法性后，将下单信息加入阻塞队列
        //库存足够则创建一个订单
        VoucherOrder order = new VoucherOrder();
        //订单id
        order.setId(redisIdWorker.nextId("order"));
        //用户id
        order.setUserId(UserHolder.getUser().getId());
        //购买的代金卷id
        order.setVoucherId(voucherId);
        //将订单信息加入阻塞队列
        orderTasks.add(order);
        //在主线程获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单ID
        Long OrderId = redisIdWorker.nextId("order");
        return Result.ok(OrderId);
    }

    /**
     * 旧版，基于java代码实现优惠卷合法性判断
     * @param voucherId
     * @return
     */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//
//        //根据id查询数据库
//        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
//
//        //判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始！");
//        }
//
//        //判断优惠卷是否过期
//        if (LocalDateTime.now().isAfter(voucher.getEndTime())) {
//            return Result.fail("该优惠卷已过期");
//        }
//
//        //查看库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("该优惠卷已售光");
//        }
//
//        Long id = UserHolder.getUser().getId();
//        RLock lock = redissonClient.getLock("lock:order:" + id);
//        if (!lock.tryLock()) {
//            return Result.fail("不可重复下单");
//        }
//
//        try {
//            //解决事务失效的情况(当前方法没有添加事务，调用的方法添加了事务)
//            //1.获取代理对象当前类的
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            //2.使用代理对象来调用具有事务的方法
//            return Result.ok(proxy.GetcreateVoucherOrder(voucherId));
//        } finally {
//            lock.unlock();
//        }
//    }

    /**
     * 创建订单操作
     * @param voucherOrder 订单信息得实体类
     * @return
     */
    @Transactional
    @Override
    public void GetcreateVoucherOrder(VoucherOrder voucherOrder) {
        //实现一人一单
        //实现每个用户维护一把锁,使用intern保证每个用户拿到的锁是相同的
            Integer count = lambdaQuery()
                    .eq(VoucherOrder::getUserId, voucherOrder.getUserId())
                    .eq(VoucherOrder::getVoucherId, voucherOrder.getUserId())
                    .count();
            if (count > 0) {
                log.error("用户重复购买");
            }
            //扣减库存
            boolean success = iSeckillVoucherService.lambdaUpdate()
                    .setSql("stock= stock -1")
                    .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                    .gt(SeckillVoucher::getStock, 0)
                    .update();
            if (!success) {
                log.error("库存不足");
            }
            save(voucherOrder);
    }
}
