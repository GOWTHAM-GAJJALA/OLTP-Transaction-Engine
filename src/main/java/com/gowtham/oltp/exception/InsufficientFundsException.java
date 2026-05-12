package com.gowtham.oltp.exception;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(Long accountId, java.math.BigDecimal requested, java.math.BigDecimal available) {
        super(String.format("Insufficient funds for account %d: requested=%.4f, available=%.4f",
                accountId, requested, available));
    }
}
