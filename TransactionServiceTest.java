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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private LedgerAccountRepository ledgerAccountRepository;
    @Mock private IdempotencyCache idempotencyCache;
    @Mock private TransactionMetrics metrics;
    @Mock private Timer.Sample timerSample;

    @InjectMocks private TransactionService transactionService;

    private LedgerAccount testAccount;
    private TransactionDto.Request creditRequest;
    private TransactionDto.Request debitRequest;

    @BeforeEach
    void setUp() {
        testAccount = LedgerAccount.builder()
                .id(1L)
                .accountNumber("ACC-001")
                .ownerName("Test User")
                .balance(new BigDecimal("1000.00"))
                .currency("USD")
                .status(LedgerAccount.AccountStatus.ACTIVE)
                .version(0L)
                .build();

        creditRequest = TransactionDto.Request.builder()
                .idempotencyKey("idem-key-credit-001")
                .accountId(1L)
                .amount(new BigDecimal("200.00"))
                .type(Transaction.TransactionType.CREDIT)
                .build();

        debitRequest = TransactionDto.Request.builder()
                .idempotencyKey("idem-key-debit-001")
                .accountId(1L)
                .amount(new BigDecimal("300.00"))
                .type(Transaction.TransactionType.DEBIT)
                .build();
    }

    @Test
    @DisplayName("Credit transaction should increase account balance")
    void creditTransaction_shouldIncreaseBalance() {
        when(idempotencyCache.exists(anyString())).thenReturn(false);
        when(ledgerAccountRepository.findByIdWithOptimisticLock(1L)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t = Transaction.builder()
                    .id(100L).idempotencyKey(t.getIdempotencyKey())
                    .accountId(t.getAccountId()).amount(t.getAmount())
                    .type(t.getType()).status(Transaction.TransactionStatus.COMPLETED)
                    .build();
            return t;
        });
        when(metrics.startTimer()).thenReturn(timerSample);

        TransactionDto.Response response = transactionService.processTransaction(creditRequest);

        assertThat(response.getStatus()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
        assertThat(response.isDuplicate()).isFalse();
        assertThat(testAccount.getBalance()).isEqualByComparingTo("1200.00");
        verify(ledgerAccountRepository).save(testAccount);
    }

    @Test
    @DisplayName("Debit transaction should decrease account balance")
    void debitTransaction_shouldDecreaseBalance() {
        when(idempotencyCache.exists(anyString())).thenReturn(false);
        when(ledgerAccountRepository.findByIdWithOptimisticLock(1L)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            return Transaction.builder().id(101L).idempotencyKey(t.getIdempotencyKey())
                    .accountId(t.getAccountId()).amount(t.getAmount())
                    .type(t.getType()).status(Transaction.TransactionStatus.COMPLETED).build();
        });
        when(metrics.startTimer()).thenReturn(timerSample);

        TransactionDto.Response response = transactionService.processTransaction(debitRequest);

        assertThat(response.getStatus()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
        assertThat(testAccount.getBalance()).isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("Debit exceeding balance should throw InsufficientFundsException")
    void debit_exceedingBalance_shouldThrow() {
        TransactionDto.Request overdrawnRequest = TransactionDto.Request.builder()
                .idempotencyKey("idem-key-over-001")
                .accountId(1L)
                .amount(new BigDecimal("9999.00"))
                .type(Transaction.TransactionType.DEBIT)
                .build();

        when(idempotencyCache.exists(anyString())).thenReturn(false);
        when(ledgerAccountRepository.findByIdWithOptimisticLock(1L)).thenReturn(Optional.of(testAccount));
        when(metrics.startTimer()).thenReturn(timerSample);

        assertThatThrownBy(() -> transactionService.processTransaction(overdrawnRequest))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    @DisplayName("Duplicate idempotency key should return duplicate response without DB write")
    void duplicateIdempotencyKey_shouldReturnDuplicateResponse() {
        when(idempotencyCache.exists("idem-key-credit-001")).thenReturn(true);
        when(metrics.startTimer()).thenReturn(timerSample);

        TransactionDto.Response response = transactionService.processTransaction(creditRequest);

        assertThat(response.isDuplicate()).isTrue();
        assertThat(response.getStatus()).isEqualTo(Transaction.TransactionStatus.DUPLICATE);
        verifyNoInteractions(ledgerAccountRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Account not found should throw AccountNotFoundException")
    void accountNotFound_shouldThrow() {
        when(idempotencyCache.exists(anyString())).thenReturn(false);
        when(ledgerAccountRepository.findByIdWithOptimisticLock(1L)).thenReturn(Optional.empty());
        when(metrics.startTimer()).thenReturn(timerSample);

        assertThatThrownBy(() -> transactionService.processTransaction(creditRequest))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
