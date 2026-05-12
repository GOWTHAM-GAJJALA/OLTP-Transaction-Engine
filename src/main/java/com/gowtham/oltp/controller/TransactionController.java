package com.gowtham.oltp.controller;

import com.gowtham.oltp.model.TransactionDto;
import com.gowtham.oltp.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    /**
     * POST /api/v1/transactions
     * Submit a debit or credit transaction. Idempotent via idempotency-key.
     */
    @PostMapping
    public ResponseEntity<TransactionDto.Response> processTransaction(
            @Valid @RequestBody TransactionDto.Request request) {

        log.info("Received transaction request: account={}, type={}, amount={}",
                request.getAccountId(), request.getType(), request.getAmount());

        TransactionDto.Response response = transactionService.processTransaction(request);

        HttpStatus status = response.isDuplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * POST /api/v1/transactions/transfer
     * Atomic fund transfer between two accounts.
     */
    @PostMapping("/transfer")
    public ResponseEntity<TransactionDto.Response> processTransfer(
            @Valid @RequestBody TransactionDto.TransferRequest request) {

        log.info("Received transfer request: from={}, to={}, amount={}",
                request.getFromAccountId(), request.getToAccountId(), request.getAmount());

        TransactionDto.Response response = transactionService.processTransfer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
