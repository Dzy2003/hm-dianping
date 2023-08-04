package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.api.R;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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
    @Resource
    ISeckillVoucherService iSeckillVoucherService;
    @Resource
    RedisIdWorker redisIdWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {

        //根据id查询数据库
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);

        //判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }

        //判断优惠卷是否过期
        if(LocalDateTime.now().isAfter(voucher.getEndTime())){
            return Result.fail("该优惠卷已过期");
        }

        //查看库存是否充足
        if(voucher.getStock() < 1){
            return Result.fail("该优惠卷已售光");
        }

        Long id = UserHolder.getUser().getId();
        //实现每个用户维护一把锁,使用intern保证每个用户拿到的锁是相同的,并且将整个函数(事务)都包在锁中
        synchronized (id.toString().intern()) {
            //解决事务失效的情况(当前方法没有添加事务，调用的方法添加了事务)
            //1.获取代理对象当前类的
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //2.使用代理对象来调用具有事务的方法
            return proxy.GetcreateVoucherOrder(voucherId);
        }

    }
    @Transactional
    @Override
    public Result GetcreateVoucherOrder(Long voucherId) {
        //实现一人一单
        Long id = UserHolder.getUser().getId();
            Integer count = lambdaQuery()
                    .eq(VoucherOrder::getUserId, id)
                    .eq(VoucherOrder::getVoucherId, voucherId)
                    .count();
            if (count > 0) {
                return Result.fail("每位用户仅能购买一次");
            }

            //扣减库存
            boolean success = iSeckillVoucherService.lambdaUpdate()
                    .setSql("stock= stock -1")
                    .eq(SeckillVoucher::getVoucherId, voucherId)
                    .gt(SeckillVoucher::getStock, 0)
                    .update();
            if (!success) {
                return Result.fail("库存不足");
            }

            //库存足够则创建一个订单
            VoucherOrder order = new VoucherOrder();
            //订单id
            order.setId(redisIdWorker.nextId("order"));
            //用户id
            order.setUserId(UserHolder.getUser().getId());
            //购买的代金卷id
            order.setVoucherId(voucherId);
            save(order);
            return Result.ok(order.getId());
    }
}
