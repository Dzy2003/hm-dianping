package com.hmdp;

import cn.hutool.core.lang.func.Func1;
import com.hmdp.entity.User;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    ShopServiceImpl service;
    @Test
    public void test () throws Exception {
        service.saveShop2Redis(1l,10l);
    }
}
