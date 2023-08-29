package com.hmdp.utils;

public interface ILock {
    /**
     * 获取锁
     * @param timeoutSec 锁持有的的超时，过期自动释放
     * @return 锁是否获取成功
     */
    boolean tryLock(Long timeoutSec);

    void unlock();
}
