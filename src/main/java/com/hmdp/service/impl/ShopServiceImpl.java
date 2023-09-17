package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
//        Function<Long,Shop> function = (Long d) ->{
//            return this.getById(d);
//        };
        Shop shop = cacheClient.queryWithMutex(
                RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        if(shop == null) {return Result.fail("店铺不存在");}
        return Result.ok(shop);
    }

    /**
     * 互斥锁解决互斥击穿
     * @param id
     * @return 商户对象
     */
    public Shop getShopByMutex(Long id){
        //用key查询redis是否存在缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopStr = stringRedisTemplate.opsForValue().get(key);
        //缓存命中直接将数据返回
        if(StrUtil.isNotBlank(shopStr)) {
            return JSONUtil.toBean(shopStr, Shop.class);
        }
        //判断命中的值是否是空值,为空则解决缓存击穿
        if ("".equals(shopStr)) {
            //返回一个错误信息
            return null;
        }
        //未命中,进行缓存重构
        Shop shop = null;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        try {
            Boolean isLock = getLock(lockKey);
            //没有拿到锁，说明有其他线程在重构缓存
            if(!isLock){
                Thread.sleep(50);
                //递归重试，直到拿到锁的线程重构缓存成功
                return getShopByMutex(id);
            }
            //拿到锁开始重构缓存
            shop = getById(id);
            //模拟重建缓存
            Thread.sleep(200);
            //数据库不存在数据向redis缓存空值解决缓存穿透
            if(shop == null){
                stringRedisTemplate.opsForValue().set(key,""
                        ,RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //缓存重构
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop)
                    ,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
        }catch (Exception e){
            throw new RuntimeException(e);
        }finally {
            //无论重构是否成功都需要释放锁
            unlock(lockKey);
        }
        return shop;
    }

    /**
     * 设置逻辑过期时间解决缓存击穿
     * @param id
     * @return
     */
    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        //用key查询redis是否存在缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopStr = stringRedisTemplate.opsForValue().get(key);
        //缓存未命中直接返回错误信息
        if(StrUtil.isBlank(shopStr)) {
            return null;
        }

        //命中之后我们需要查看存入redis的过期时间是否过期。
        RedisData redisData = JSONUtil.toBean(shopStr,RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        /*判断逻辑过期时间是否过期：
            1.未过期直接将数据返回
            2.过期则将数据返回并开启一个线程来更新缓存
        */
        //未过期
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return shop;
        }
        //过期
        log.debug("缓存过期");
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        //获得锁则将开启一个新的线程缓存进行重建
        if(getLock(lockKey)){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2Redis(id, RedisConstants.CACHE_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        //未获得锁则直接返回旧数据
        return shop;

    }

    /**
     * 缓存预热先向数据库中添加缓存并设置逻辑过期时间
     * @param id
     * @param expireSeconds
     */
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        log.debug("缓存过期，重建缓存。。。");
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装redis数据
        RedisData data = new RedisData();
        data.setData(shop);
        //逻辑过期时间
        data.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(data));
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

    @Override
    @Transactional
    public void update(Shop shop) {
        if(shop.getId() == null) throw new RuntimeException("店铺id不能为空");
        //存入数据库
        updateById(shop);
        //删除缓存保持数据的一致性
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //为获取位置坐标按照分页查询返回
        if(x == null || y == null){
            return Result.ok(lambdaQuery().eq(Shop::getTypeId,typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE)));
        }
        //分页起始记录和终止记录
        int begin = (current-1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //从redis中查询在当前用户坐标附件的商铺，按照距离从近到远
        String key = RedisConstants.SHOP_GEO_KEY + typeId.toString();
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate
                .opsForGeo()
                //传入key，用户坐标，半径
                .search(key, GeoReference.fromCoordinate(x, y), new Distance(5000),
                //参数，limit(查询记录个数)，includeDistance(返回结果中包含距离)
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().limit(end).includeDistance());
        //为空则说明附近没有商铺直接返回空集合
        if (results == null) return Result.ok(Collections.emptyList());
        //获取查询内容
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoResults = results.getContent();
        //说明已经到最后一页
        if(geoResults.size() <= begin) return Result.ok(Collections.emptyList());
        //记录店铺id和距离用户的距离
        Map<String, Distance> distanceMap = new HashMap<>(geoResults.size());
        //获取到当前分页的店铺id，并封装distanceMap
        String ids = geoResults.stream().skip(begin).map(geoResult -> {
            String idStr = geoResult.getContent().getName();
            distanceMap.put(idStr, geoResult.getDistance());
            return idStr;
        }).collect(Collectors.joining(","));
        log.info(ids);
        //根据传入的ids值顺序查询商铺并封装distance，以相同的顺序返回
        List<Shop> shopList = lambdaQuery().inSql(Shop::getId, ids)
                //保证查询结构顺序与id传入顺序相同
                .last("ORDER BY FIELD(id," + ids + ")").list()
                //封装shop的distance属性
                .stream().map(shop -> {
                    Distance distance = distanceMap.get(shop.getId().toString());
                    shop.setDistance(distance.getValue());
                    return shop;
                })
                .collect(Collectors.toList());
        log.info(shopList.toString());
        return Result.ok(shopList);
    }
}
