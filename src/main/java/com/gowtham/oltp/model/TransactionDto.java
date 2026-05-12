package com.gowtham.oltp.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TransactionDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {

        @NotBlank(message = "Idempotency key is required")
        @Size(min = 16, max = 64, message = "Idempotency key must be 16-64 characters")
        private String idempotencyKey;

        @NotNull(message = "Account ID is required")
        private Long accountId;

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be positive")
        private BigDecimal amount;

        @NotNull(message = "Transaction type is required")
        private Transaction.TransactionType type;

        @Size(max = 100)
        private String referenceId;

        @Size(max = 255)
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long transactionId;
        private String idempotencyKey;
        private Long accountId;
        private BigDecimal amount;
        private Transaction.TransactionType type;
        private Transaction.TransactionStatus status;
        private String referenceId;
        private LocalDateTime createdAt;
        private boolean duplicate;
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferRequest {

        @NotBlank
        @Size(min = 16, max = 64)
        private String idempotencyKey;

        @NotNull
        private Long fromAccountId;

        @NotNull
        private Long toAccountId;

        @NotNull
        @Positive
        private BigDecimal amount;

        @Size(max = 255)
        private String description;
    }
}
