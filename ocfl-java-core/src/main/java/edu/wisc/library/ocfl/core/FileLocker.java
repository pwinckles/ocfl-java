/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 University of Wisconsin Board of Regents
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package edu.wisc.library.ocfl.core;

import edu.wisc.library.ocfl.api.exception.LockException;
import edu.wisc.library.ocfl.api.util.Enforce;
import edu.wisc.library.ocfl.core.util.UncheckedCallable;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides locks for logical paths, so that an object may be safely modified by multiple threads.
 */
public class FileLocker {

    private static final Logger log = LoggerFactory.getLogger(FileLocker.class);

    private final Map<String, ReentrantLock> locks;
    private final long timeoutMillis;

    /**
     * @param timeoutDuration the max amount of time to wait for a file lock
     */
    public FileLocker(Duration timeoutDuration) {
        this.timeoutMillis = Enforce.notNull(timeoutDuration, "timeoutDuration cannot be null")
                .toMillis();
        locks = new ConcurrentHashMap<>();
    }

    /**
     * Returns a lock on the specified logical path or throws a {@link LockException} if a lock was unable to be
     * acquired. This lock MUST be released in a finally block.
     *
     * @param logicalPath the path to lock
     * @return the lock
     * @throws LockException when unable to acquire a lock
     */
    public ReentrantLock lock(String logicalPath) {
        var lock = locks.computeIfAbsent(logicalPath, k -> new ReentrantLock());
        log.debug("Acquiring lock on {}", logicalPath);
        boolean acquired;
        try {
            acquired = lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockException("Failed to acquire lock on file " + logicalPath, e);
        }
        if (acquired) {
            log.debug("Acquired lock on {}", logicalPath);
            return lock;
        } else {
            throw new LockException("Failed to acquire lock on file " + logicalPath);
        }
    }

    /**
     * Executes the runnable after acquire a lock on the specified logical path. If the lock cannot be acquired,
     * a {@link LockException} is thrown.
     *
     * @param logicalPath the path to lock
     * @throws LockException when unable to acquire a lock
     */
    public void withLock(String logicalPath, Runnable runnable) {
        var lock = lock(logicalPath);
        try {
            runnable.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Executes the callable after acquire a lock on the specified logical path. If the lock cannot be acquired,
     * a {@link LockException} is thrown.
     *
     * @param logicalPath the path to lock
     * @return the output of the callable
     * @throws LockException when unable to acquire a lock
     */
    public <T> T withLock(String logicalPath, UncheckedCallable<T> callable) {
        var lock = lock(logicalPath);
        try {
            return callable.call();
        } finally {
            lock.unlock();
        }
    }
}
