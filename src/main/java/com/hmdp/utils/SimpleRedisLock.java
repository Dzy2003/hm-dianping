package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * redis实现的分布式锁
 */
public class SimpleRedisLock implements ILock {
    //锁的前缀
    private static final String KEY_PREFIX="lock:";;;
    //标识业务名称
    private String name ;
    private StringRedisTemplate stringRedisTemplate;
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate,String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name ;
    }

    /**
     * 获取锁
     * @param timeoutSec 锁持有的的超时，过期自动释放
     * @return
     */
    @Override
    public boolean tryLock(Long timeoutSec) {
        //获取线程ID
        String ThreadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, ThreadId,
                timeoutSec, TimeUnit.SECONDS);
        if(success == null) return false;
        return success;
    }

    /**
     * 释放锁
     */
    @Override
    public void unlock() {
        //锁的key
        String key = KEY_PREFIX + name;
        //线程标识
        String ThreadId = stringRedisTemplate.opsForValue().get(key);
        //比较当前线程标识是否和存入redis中的线程标识相同，相同则释放锁
        if(ThreadId .equals(ID_PREFIX + Thread.currentThread().getId())){
            stringRedisTemplate.delete(key);
        }
    }
}
