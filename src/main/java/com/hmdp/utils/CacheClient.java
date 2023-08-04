package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
/**
 * 缓存操作的工具类
 */
public class CacheClient {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    /**
     * 向redis中存入数据并指定TTL
     * @param key 键
     * @param value 数据
     * @param time TTL
     * @param unit TTL的单位
     * @param <T> 数据类型
     */
    public  <T> void set(String key, T value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 向redis中存入数据并指定逻辑TTL
     * @param key 键
     * @param value 数据
     * @param time TTL
     * @param unit TTL的单位
     * @param <T> 数据类型
     */
    public  <T> void setWithLogicalExpire(String key, T value, Long time, TimeUnit unit){
        //封装RedisData
        RedisData data = new RedisData();
        data.setData(value);
        //计算逻辑过期时间
        data.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(data));
    }

    /**
     * 查询数据并通过向redis中存入控制的方案解决缓存穿透
     * @param prefixKey 键的前缀
     * @param id 数据id
     * @param type redis获得的数据反序列化的类型
     * @param dbFallback 函数式接口，传入lambda表达式重写其中的apply方法，用于返回数据库的查询结果
     * @param time 向redis存入空字符串的TTL
     * @param unit TTL的单位
     * @param <R> 返回的数据
     * @param <ID> 根据id查询数据库
     * @return 返回从缓存或者数据库得到的数据
     */
    public <R,ID> R queryWithPassThrough(
            String prefixKey, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        //用key查询redis是否存在缓存
        String key = prefixKey + id;
        String JSONStr = stringRedisTemplate.opsForValue().get(key);
        if("".equals(JSONStr)){
            return null;
        }
        //缓存命中直接将数据返回
        if(JSONStr != null) {
            return JSONUtil.toBean(JSONStr,type);
        }
        //未命中，从数据库中查询数据。
        R r = dbFallback.apply(id);

        //解决缓存穿透，数据库中也不存在的数据向redis缓存空值。
        if(r == null){
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //将数据库查出的结果添加到redis形成缓存
        this.set(key, r, time, unit);

        return r;
    }

    /**
     * 查询数据
     * 1.通过互斥锁解决缓存击穿
     * 2.通过写入空值解决缓存穿透
     * @param prefixKey 键的前缀
     * @param id 数据id
     * @param type redis获得的数据反序列化的类型
     * @param dbFallback 函数式接口，传入lambda表达式重写其中的apply方法，用于返回数据库的查询结果
     * @param time 缓存重建的TTL
     * @param unit TTL的单位
     * @param <R> 返回的数据
     * @param <ID> 根据id查询数据库
     * @return
     */
    public <R,ID> R queryWithMutex(
            String prefixKey, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        //用key查询redis是否存在缓存
        String key = prefixKey + id;
        String JSONStr = stringRedisTemplate.opsForValue().get(key);
        //缓存命中直接将数据返回
        if(StrUtil.isNotBlank(JSONStr)) {
            return JSONUtil.toBean(JSONStr, type);
        }
        //判断命中的值是否是空值,为空则解决缓存击穿
        if ("".equals(JSONStr)) {
            //返回一个错误信息
            return null;
        }
        //未命中,进行缓存重构
        R r = null;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        try {
            Boolean isLock = getLock(lockKey);
            //没有拿到锁，说明有其他线程在重构缓存
            if(!isLock){
                Thread.sleep(50);
                //递归重试，直到拿到锁的线程重构缓存成功
                return queryWithMutex(prefixKey,id,type,dbFallback,time,unit);
            }
            //拿到锁开始重构缓存
            r = dbFallback.apply(id);
            //模拟重建缓存
            Thread.sleep(200);
            //数据库不存在数据向redis缓存空值解决缓存穿透
            if(r == null){
                stringRedisTemplate.opsForValue().set(key,""
                        ,RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //缓存重构
            set(key,r,time,unit);
        }catch (Exception e){
            throw new RuntimeException(e);
        }finally {
            //无论重构是否成功都需要释放锁
            unlock(lockKey);
        }
        return r;
    }

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 查询数据
     * 1.通过逻辑过期时间解决缓存击穿的问题
     * 2.这种方式需要对缓存进行预热，不存在缓存穿透问题。
     * @param prefixKey 键的前缀
     * @param id 数据id
     * @param type redis获得的数据反序列化的类型
     * @param dbFallback 函数式接口，传入lambda表达式重写其中的apply方法，用于返回数据库的查询结果
     * @param time 缓存重建的TTL
     * @param unit TTL的单位
     * @param <R> 返回的数据
     * @param <ID> 根据id查询数据库
     * @return
     */
    public <R,ID> R queryWithLogicalExpire(
            String prefixKey, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        //用key查询redis是否存在缓存
        String key = prefixKey + id;
        String JSONStr = stringRedisTemplate.opsForValue().get(key);

        //缓存未命中直接返回错误信息
        if(StrUtil.isBlank(JSONStr)) {
            return null;
        }

        //命中之后我们需要查看存入redis的过期时间是否过期。
        RedisData redisData = JSONUtil.toBean(JSONStr,RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        /*判断逻辑过期时间是否过期：
            1.未过期直接将数据返回
            2.过期则将数据返回并开启一个线程来更新缓存
        */
        //未过期
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return r;
        }
        //过期
        log.debug("缓存过期");
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        //获得锁则将开启一个新的线程缓存进行重建
        if(getLock(lockKey)){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R newR = dbFallback.apply(id);
                    setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        //未获得锁则直接返回旧数据
        return r;

    }


    /**
     * 获取互斥锁
     * @param key
     * @return
     */
    public Boolean getLock(String key){
        //只有redis中不存在该key时才返回true
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent
                (key, "1",RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        if(flag == null){
            return false;
        }
        return flag;
    }

    /**
     * 释放互斥锁
     * @param key
     */
    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
