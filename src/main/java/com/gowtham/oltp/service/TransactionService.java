package com.gowtham.oltp.service;

import com.gowtham.oltp.cache.IdempotencyCache;
import com.gowtham.oltp.exception.AccountNotFoundException;
import com.gowtham.oltp.exception.InsufficientFundsException;
import com.gowtham.oltp.metrics.TransactionMetrics;
import com.gowtham.oltp.model.LedgerAccount;
import com.gowtham.oltp.model.Transaction;
import com.gowtham.oltp.model.TransactionDto;
import com.gowtham.oltp.repository.LedgerAccountRepository;
import com.gowtham.oltp.repository.TransactionRepository;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Core transaction processing service.
 *
 * Handles:
 * - Idempotency key check (Redis) before touching the DB
 * - ACID-safe ledger debit/credit with optimistic locking
 * - Automatic retry on optimistic lock conflicts (up to 3 attempts)
 * - Prometheus metric recording for every outcome
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private static final int MAX_LOCK_RETRIES = 3;

    private final TransactionRepository transactionRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final IdempotencyCache idempotencyCache;
    private final TransactionMetrics metrics;

    /**
     * Processes a single debit or credit transaction.
     * Idempotent: repeated calls with the same key return the original result.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = MAX_LOCK_RETRIES,
            backoff = @Backoff(delay = 50, multiplier = 2)
    )
    public TransactionDto.Response processTransaction(TransactionDto.Request request) {
        Timer.Sample timerSample = metrics.startTimer();

        // 1. Check idempotency cache before any DB work
        if (idempotencyCache.exists(request.getIdempotencyKey())) {
            log.info("Duplicate transaction detected via cache: key={}", request.getIdempotencyKey());
            metrics.recordDuplicate();
            return buildDuplicateResponse(request);
        }

        try {
            // 2. Load account with optimistic lock
            LedgerAccount account = ledgerAccountRepository
                    .findByIdWithOptimisticLock(request.getAccountId())
                    .orElseThrow(() -> new AccountNotFoundException(request.getAccountId()));

            // 3. Apply balance change
            applyBalanceChange(account, request);
            ledgerAccountRepository.save(account);

            // 4. Persist the transaction record
            Transaction txn = Transaction.builder()
                    .idempotencyKey(request.getIdempotencyKey())
                    .accountId(request.getAccountId())
                    .amount(request.getAmount())
                    .type(request.getType())
                    .status(Transaction.TransactionStatus.COMPLETED)
                    .referenceId(request.getReferenceId())
                    .description(request.getDescription())
                    .build();

            transactionRepository.save(txn);

            // 5. Reserve key in Redis (release on failure in catch block)
            idempotencyCache.tryReserve(request.getIdempotencyKey(), String.valueOf(txn.getId()));

            metrics.recordTransaction(request.getType().name(), "COMPLETED");
            metrics.stopTimer(timerSample, request.getType().name(), "COMPLETED");

            log.info("Transaction completed: id={}, account={}, type={}, amount={}",
                    txn.getId(), request.getAccountId(), request.getType(), request.getAmount());

            return toResponse(txn, false);

        } catch (ObjectOptimisticLockingFailureException ex) {
            metrics.recordOptimisticLockRetry();
            log.warn("Optimistic lock conflict on account {}, retrying...", request.getAccountId());
            throw ex; // triggers @Retryable
        } catch (Exception ex) {
            idempotencyCache.release(request.getIdempotencyKey());
            metrics.recordTransaction(request.getType().name(), "FAILED");
            metrics.stopTimer(timerSample, request.getType().name(), "FAILED");
            throw ex;
        }
    }

    /**
     * Atomic fund transfer between two accounts.
     * Locks accounts in consistent ID order to prevent deadlocks.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = MAX_LOCK_RETRIES,
            backoff = @Backoff(delay = 50, multiplier = 2)
    )
    public TransactionDto.Response processTransfer(TransactionDto.TransferRequest request) {
        if (idempotencyCache.exists(request.getIdempotencyKey())) {
            metrics.recordDuplicate();
            return TransactionDto.Response.builder()
                    .idempotencyKey(request.getIdempotencyKey())
                    .status(Transaction.TransactionStatus.DUPLICATE)
                    .duplicate(true)
                    .message("Duplicate transfer request")
                    .build();
        }

        // Load both accounts — always in ascending ID order to avoid deadlocks
        Long lowId = Math.min(request.getFromAccountId(), request.getToAccountId());
        Long highId = Math.max(request.getFromAccountId(), request.getToAccountId());

        LedgerAccount low = ledgerAccountRepository.findByIdWithOptimisticLock(lowId)
                .orElseThrow(() -> new AccountNotFoundException(lowId));
        LedgerAccount high = ledgerAccountRepository.findByIdWithOptimisticLock(highId)
                .orElseThrow(() -> new AccountNotFoundException(highId));

        LedgerAccount from = low.getId().equals(request.getFromAccountId()) ? low : high;
        LedgerAccount to = low.getId().equals(request.getToAccountId()) ? low : high;

        if (from.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException(from.getId(), request.getAmount(), from.getBalance());
        }

        from.setBalance(from.getBalance().subtract(request.getAmount()));
        to.setBalance(to.getBalance().add(request.getAmount()));

        ledgerAccountRepository.save(from);
        ledgerAccountRepository.save(to);

        Transaction txn = Transaction.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .accountId(request.getFromAccountId())
                .amount(request.getAmount())
                .type(Transaction.TransactionType.TRANSFER)
                .status(Transaction.TransactionStatus.COMPLETED)
                .description(request.getDescription())
                .build();

        transactionRepository.save(txn);
        idempotencyCache.tryReserve(request.getIdempotencyKey(), String.valueOf(txn.getId()));

        metrics.recordTransaction("TRANSFER", "COMPLETED");
        return toResponse(txn, false);
    }

    // ─── Private Helpers ────────────────────────────────────────────────────────

    private void applyBalanceChange(LedgerAccount account, TransactionDto.Request request) {
        switch (request.getType()) {
            case DEBIT -> {
                if (account.getBalance().compareTo(request.getAmount()) < 0) {
                    throw new InsufficientFundsException(account.getId(),
                            request.getAmount(), account.getBalance());
                }
                account.setBalance(account.getBalance().subtract(request.getAmount()));
            }
            case CREDIT -> account.setBalance(account.getBalance().add(request.getAmount()));
            default -> throw new IllegalArgumentException("Use processTransfer() for TRANSFER transactions");
        }
    }

    private TransactionDto.Response buildDuplicateResponse(TransactionDto.Request request) {
        return TransactionDto.Response.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .accountId(request.getAccountId())
                .amount(request.getAmount())
                .type(request.getType())
                .status(Transaction.TransactionStatus.DUPLICATE)
                .duplicate(true)
                .message("Duplicate request — original transaction already processed")
                .build();
    }

    private TransactionDto.Response toResponse(Transaction txn, boolean duplicate) {
        return TransactionDto.Response.builder()
                .transactionId(txn.getId())
                .idempotencyKey(txn.getIdempotencyKey())
                .accountId(txn.getAccountId())
                .amount(txn.getAmount())
                .type(txn.getType())
                .status(txn.getStatus())
                .referenceId(txn.getReferenceId())
                .createdAt(txn.getCreatedAt())
                .duplicate(duplicate)
                .build();
    }
}
