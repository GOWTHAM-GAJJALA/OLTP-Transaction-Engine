# High-Throughput OLTP Transaction Processing Engine

A production-grade, multithreaded financial transaction processing engine built with **Java 17** and **Spring Boot**. Designed for OLTP workloads requiring ACID compliance, idempotency, and sub-10ms latency under concurrent load.

## Architecture Overview

```
Client → REST API → TransactionService → LedgerAccount (Optimistic Lock)
                          ↓                      ↓
                   IdempotencyCache (Redis)   Transaction (DB)
                          ↓
                   Prometheus Metrics → Grafana
```

## Key Design Decisions

| Concern | Solution |
|---|---|
| Duplicate submissions | Redis idempotency cache (SET NX, 24h TTL) |
| Concurrent balance updates | JPA `@Version` optimistic locking |
| Deadlock prevention on transfers | Lock accounts in ascending ID order |
| Lock conflict recovery | `@Retryable` with exponential backoff (3 attempts) |
| High concurrency | Configurable `ThreadPoolTaskExecutor` with backpressure |
| Observability | Custom Prometheus gauges for queue depth + active threads |

## Tech Stack

- **Java 17** — records, pattern matching, sealed types
- **Spring Boot 3.2** — web, JPA, actuator, validation
- **PostgreSQL** — ledger storage with sequences and optimistic locking
- **Redis (Lettuce)** — idempotency cache with TTL
- **Micrometer + Prometheus** — custom transaction metrics
- **Docker + Docker Compose** — full local environment
- **JUnit 5 + Mockito** — unit test coverage

## Getting Started

### Prerequisites
- Docker and Docker Compose
- Java 17+ (for local development)

### Run with Docker Compose

```bash
git clone https://github.com/gowtham-gajjala/oltp-transaction-engine
cd oltp-transaction-engine
docker-compose up -d
```

Services started:
- App: http://localhost:8080
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)

### Run Locally

```bash
# Start dependencies only
docker-compose up -d postgres redis

# Run the app
./mvnw spring-boot:run
```

## API Reference

### Submit a Transaction

```http
POST /api/v1/transactions
Content-Type: application/json

{
  "idempotencyKey": "unique-key-abc-123",
  "accountId": 1,
  "amount": 250.00,
  "type": "DEBIT",
  "description": "Monthly subscription"
}
```

**Response (201 Created):**
```json
{
  "transactionId": 42,
  "idempotencyKey": "unique-key-abc-123",
  "accountId": 1,
  "amount": 250.00,
  "type": "DEBIT",
  "status": "COMPLETED",
  "createdAt": "2024-01-15T10:30:00",
  "duplicate": false
}
```

### Fund Transfer

```http
POST /api/v1/transactions/transfer
Content-Type: application/json

{
  "idempotencyKey": "transfer-key-xyz-456",
  "fromAccountId": 1,
  "toAccountId": 2,
  "amount": 500.00,
  "description": "Rent payment"
}
```

### Health & Metrics

```bash
# Health check
curl http://localhost:8080/actuator/health

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus
```

## Custom Metrics

| Metric | Type | Description |
|---|---|---|
| `transaction_processed_total` | Counter | Total transactions by type + status |
| `transaction_processing_duration_seconds` | Timer | End-to-end processing time |
| `executor_queue_depth` | Gauge | Pending tasks in executor queue |
| `executor_active_threads` | Gauge | Active executor threads |
| `duplicate_transactions_total` | Counter | Detected duplicate submissions |
| `optimistic_lock_retries_total` | Counter | Optimistic lock conflict retries |

## Running Tests

```bash
./mvnw test
```

## Configuration

Key properties in `application.yml`:

```yaml
transaction:
  executor:
    core-pool-size: 10     # Base threads
    max-pool-size: 50      # Max threads under load
    queue-capacity: 500    # Backpressure queue depth
```
