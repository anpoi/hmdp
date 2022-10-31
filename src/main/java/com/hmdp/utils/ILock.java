package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * true：获取锁成功 false：获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
