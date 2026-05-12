package com.gowtham.oltp.metrics;

import io.micrometer.core.instrument.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom Prometheus metrics for the OLTP transaction engine.
 *
 * Exposes:
 *  - transaction_processed_total (counter, by type and status)
 *  - transaction_processing_duration_seconds (timer)
 *  - executor_queue_depth (gauge)
 *  - executor_active_threads (gauge)
 *  - duplicate_transactions_total (counter)
 *  - optimistic_lock_retries_total (counter)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionMetrics {

    private final MeterRegistry meterRegistry;

    private Counter duplicateTransactionCounter;
    private Counter optimisticLockRetryCounter;
    private AtomicLong queueDepth;
    private AtomicLong activeThreads;

    @PostConstruct
    public void init() {
        duplicateTransactionCounter = Counter.builder("duplicate_transactions_total")
                .description("Total number of duplicate transaction submissions detected")
                .register(meterRegistry);

        optimisticLockRetryCounter = Counter.builder("optimistic_lock_retries_total")
                .description("Total number of optimistic lock retries on ledger updates")
                .register(meterRegistry);

        queueDepth = new AtomicLong(0);
        Gauge.builder("executor_queue_depth", queueDepth, AtomicLong::get)
                .description("Current depth of the transaction executor task queue")
                .register(meterRegistry);

        activeThreads = new AtomicLong(0);
        Gauge.builder("executor_active_threads", activeThreads, AtomicLong::get)
                .description("Number of currently active executor threads processing transactions")
                .register(meterRegistry);

        log.info("Transaction metrics initialized and registered with Prometheus");
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopTimer(Timer.Sample sample, String type, String status) {
        sample.stop(Timer.builder("transaction_processing_duration_seconds")
                .description("Time taken to process a transaction end-to-end")
                .tag("type", type)
                .tag("status", status)
                .register(meterRegistry));
    }

    public void recordTransaction(String type, String status) {
        Counter.builder("transaction_processed_total")
                .description("Total transactions processed")
                .tag("type", type)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    public void recordDuplicate() {
        duplicateTransactionCounter.increment();
    }

    public void recordOptimisticLockRetry() {
        optimisticLockRetryCounter.increment();
    }

    public void updateExecutorStats(ThreadPoolExecutor executor) {
        queueDepth.set(executor.getQueue().size());
        activeThreads.set(executor.getActiveCount());
    }
}
