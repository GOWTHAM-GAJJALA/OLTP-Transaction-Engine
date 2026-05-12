package com.gowtham.oltp.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed idempotency cache.
 *
 * Stores idempotency keys with a TTL so that duplicate transaction submissions
 * within the window are detected and rejected without hitting the database.
 * Uses SET NX (set-if-not-exists) for atomic check-and-set semantics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyCache {

    private static final String KEY_PREFIX = "idempotency:txn:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /**
     * Attempts to reserve an idempotency key.
     * Returns true if the key was newly set (not a duplicate).
     * Returns false if the key already existed (duplicate request).
     */
    public boolean tryReserve(String idempotencyKey, String transactionId) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        Boolean wasSet = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, transactionId, TTL);

        boolean isNew = Boolean.TRUE.equals(wasSet);
        if (!isNew) {
            log.warn("Duplicate transaction detected for idempotency key: {}", idempotencyKey);
        }
        return isNew;
    }

    /**
     * Retrieves the stored transaction ID for a given idempotency key.
     */
    public Optional<String> get(String idempotencyKey) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        return Optional.ofNullable(value);
    }

    /**
     * Checks if a key exists (duplicate check without reserving).
     */
    public boolean exists(String idempotencyKey) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + idempotencyKey));
    }

    /**
     * Removes a key (used on rollback/failure so the client can retry).
     */
    public void release(String idempotencyKey) {
        redisTemplate.delete(KEY_PREFIX + idempotencyKey);
        log.debug("Released idempotency key: {}", idempotencyKey);
    }
}
