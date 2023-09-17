package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.lang.func.Func1;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.Cleanup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    ShopServiceImpl service;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    UserServiceImpl userService;
    @Resource
    ShopServiceImpl shopService;

    /**
     * 在Redis中保存1000个用户信息并将其token写入文件中，方便测试多人秒杀业务
     */
    @Test
    void testMultiLogin() throws IOException {
        List<User> userList = userService.lambdaQuery().last("limit 1000").list();
        for (User user : userList) {
            String token = UUID.randomUUID().toString(true);
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create().ignoreNullValue()
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
            String tokenKey = LOGIN_USER_KEY + token;
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            stringRedisTemplate.expire(tokenKey, 30, TimeUnit.MINUTES);
        }
        Set<String> keys = stringRedisTemplate.keys(LOGIN_USER_KEY + "*");
        @Cleanup FileWriter fileWriter = new FileWriter(System.getProperty("user.dir") + "\\tokens.txt");
        @Cleanup BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
        assert keys != null;
        for (String key : keys) {
            String token = key.substring(LOGIN_USER_KEY.length());
            String text = token + "\n";
            bufferedWriter.write(text);
        }
    }

    /**
     * 初始化店铺位置信息到redis中
     */
    @Test
    void loadShopData() {
        //查询商铺列表后放入stream流中
        shopService.list().stream()
                //将商铺列表按照类型分类
                .collect(Collectors.groupingBy(Shop::getTypeId))
                //遍历类型分类后的商铺表
                .entrySet()
                //将相同类型的商铺的id和坐标加入locations中
                .forEach(entry -> {
                    String key = RedisConstants.SHOP_GEO_KEY + entry.getKey();
                    List<Shop> shopList = entry.getValue();
                    List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shopList.size());
                    shopList.forEach(shop -> {
                        locations.add(new RedisGeoCommands.GeoLocation(
                           shop.getId().toString(),
                           new Point(shop.getX(),shop.getY())
                        ));
                    });
                    stringRedisTemplate.opsForGeo().add(key, locations);
                });
    }
}
