package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public List<ShopType> queryTypeList() {
        //用key查询redis是否存在缓存
        String key = RedisConstants.CACHE_TYPELIST_KEY;
        Boolean flag = stringRedisTemplate.hasKey(key);
        //缓存命中则将数据转换为ShopType类型集合返回
        if(flag) {
            List<ShopType> result = new ArrayList<ShopType>();
            List<String> range = stringRedisTemplate.opsForList().range(key, 0, -1);
            for (String s : range) {
                result.add(JSONUtil.toBean(s,ShopType.class));
            }
            return result;
        }
        //未命中则取数据库中查询
        List<ShopType> shopTypes = lambdaQuery().orderByAsc(ShopType::getSort).list();
        if(shopTypes == null) throw new RuntimeException();
        //将查询的数据转为json字符串
        List<String> res = new ArrayList<>();
        for (ShopType shopType : shopTypes) {
            res.add(JSONUtil.toJsonStr(shopType));
        }
        //添加到redis中
        stringRedisTemplate.opsForList().leftPushAll(key,res);
        stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shopTypes;
    }
}
