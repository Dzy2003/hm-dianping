package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Follow;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class testCacheClient {
    @Resource
    CacheClient cacheClient;
    @Resource
    RedisIdWorker redisIdWorker;
    @Test
    void testSet() {
        cacheClient.set("age","12",100l, TimeUnit.SECONDS);
    }

    @Test
    void testSetWithLogic() {
        Follow follow = new Follow();
        follow.setId(1l);
        follow.setUserId(2l);
        follow.setFollowUserId(3l);
        follow.setCreateTime(LocalDateTime.now());
        cacheClient.setWithLogicalExpire("follow:1",follow,100l,TimeUnit.SECONDS);
    }
    private static final ExecutorService executorService = Executors.newFixedThreadPool(500);
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }
}
