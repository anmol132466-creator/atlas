// src/main/java/net/atlas/auth/RateLimiter.java
package net.atlas.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimiter {

    private final int burstCapacity;
    private final int refillPerSecond;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int burstCapacity, int refillPerSecond) {
        this.burstCapacity = Math.max(1, burstCapacity);
        this.refillPerSecond = Math.max(1, refillPerSecond);
    }

    public boolean tryAcquire(String ip) {
        if (ip == null || ip.isBlank()) {
            return true;
        }
        if (buckets.size() > 2000) {
            cleanup();
        }
        TokenBucket bucket = buckets.computeIfAbsent(ip, k -> new TokenBucket(burstCapacity, refillPerSecond));
        return bucket.tryConsume();
    }

    public void cleanup() {
        long cutoff = System.currentTimeMillis() - 60000L;
        buckets.entrySet().removeIf(entry -> entry.getValue().lastRefill < cutoff);
    }

    private static final class TokenBucket {
        private final double capacity;
        private final double refillRatePerMs;
        private double tokens;
        private long lastRefill;

        private TokenBucket(int capacity, int refillPerSecond) {
            this.capacity = capacity;
            this.refillRatePerMs = (double) refillPerSecond / 1000.0;
            this.tokens = capacity;
            this.lastRefill = System.currentTimeMillis();
        }

        private synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefill;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + (elapsed * refillRatePerMs));
                lastRefill = now;
            }
        }
    }
}
