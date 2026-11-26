# ledger-service

A small **double-entry ledger** for recording payment events, built as a production-shaped
service on AWS: webhooks arrive through Lambda, flow through SNS/SQS, and are written to
Postgres by Spring Boot services running on EKS.

Every money movement is a balanced transaction: the entries in each currency sum to exactly
zero, so money is never created or lost. Balances are derived from history, never stored.

## Architecture

```
Payment provider (e.g. Razorpay)
        │  HTTPS webhook (HMAC-signed)
        ▼
 API Gateway ──► Lambda "webhook-ingest"   verifies signature (secret in Secrets Manager)
                        │ publish
                        ▼
                 SNS topic "payment-events"      (KMS-encrypted)
                        │
                        ▼
                 SQS queue + DLQ                  (KMS-encrypted)
                        │ poll
                        ▼
   ┌──────────── EKS cluster ──────────────┐
   │ ledger-worker → records transactions   │   each pod has its own IAM role (IRSA)
   │ ledger-api    → balances / history     │
   └───────────────┬────────────────────────┘
                   ▼
            Postgres (RDS)     credentials in Secrets Manager, KMS-encrypted
```

## Modules

| Module | What it is | Status |
|---|---|---|
| `ledger-core` | Domain model and rules: `Entry`, `Transaction`, `Balances`. Plain Java, no framework. | ✅ done |
| `ledger-api` | Spring Boot REST API over Postgres (Flyway, JDBC, Testcontainers). | 🚧 in progress |
| `ledger-worker` | Spring Boot SQS consumer that turns payment events into transactions. | planned |
| `webhook-lambda` | Plain-Java Lambda (SnapStart) that verifies and publishes webhooks. | planned |
| `deploy/` | Terraform (VPC, EKS, RDS, SQS/SNS, KMS, IRSA) and Kubernetes manifests. | planned |

## The domain model

A **transaction** is one money movement made of two or more **entries**. Amounts are in minor
units (paise, cents); positive is a debit, negative is a credit.

A ₹500 subscription paid through Razorpay:

| Account | Amount |
|---|---|
| `razorpay:receivable` | +50000 |
| `revenue:subscriptions` | −50000 |
| **sum** | **0** |

A transaction is rejected (`InvalidTransactionException` with a `Reason`) if:

| Rule | Reason |
|---|---|
| idempotency key is null or blank | `MISSING_IDEMPOTENCY_KEY` |
| fewer than two entries | `TOO_FEW_ENTRIES` |
| any entry has amount 0 | `ZERO_AMOUNT` |
| summing amounts overflows `long` | `OVERFLOW` |
| entries do not sum to zero **per currency** | `UNBALANCED` |

`Transaction.create(key, entries, clock)` records something new; `transaction.reverse(newKey, clock)`
produces the compensating transaction for a refund or correction. `Balances.of(account, transactions)`
derives an account's balance per currency.

## Design decisions

- **No Spring in `ledger-core`.** The business rules have no dependency on a framework, database
  or AWS. They are tested in milliseconds and reused unchanged by the API, the worker and the Lambda.
- **Validation lives in the record's compact constructor**, not only in `create()`. Transactions are
  also rebuilt from database rows, and every path goes through the same rules, so an invalid
  `Transaction` cannot exist in memory.
- **Money is `long` minor units, never `double`.** Floating point cannot represent money exactly.
  All arithmetic uses `Math.addExact` / `Math.negateExact`, so overflow is reported instead of
  silently wrapping.
- **Balanced per currency.** +₹500 cannot balance −$500; each currency must net to zero on its own.
- **Immutable and append-only.** Records copy their input (`List.copyOf`), and history is never
  edited or deleted: a mistake is undone by posting a reversal with its own idempotency key, so the
  audit trail stays complete.
- **Idempotency keys are mandatory.** Webhooks and SQS deliver at least once, so the same event will
  arrive more than once. The key (the provider's event id) is what lets the database recognise a
  duplicate; see [Persistence](#persistence-ledger-api).
- **Time comes from an injected `Clock`**, not `Instant.now()`, so timestamps are deterministic in tests.
- **Two kinds of errors.** `IllegalArgumentException` / `NullPointerException` mean a programming
  error (e.g. an `Entry` without an account); `InvalidTransactionException` means a business rule was
  broken and is mapped to an HTTP 422 by the API.
- **Zero balances are kept.** An account whose INR activity nets to zero reports `{INR=0}`, not `{}`,
  because it has history in that currency.
- **Balances are derived, not stored.** The sum of every account's balance in a currency is always
  zero; this invariant is covered by a test.

## Persistence (`ledger-api`)

> In progress: the module, its Testcontainers setup and the decisions below are in place; the schema,
> repository and endpoints are being built in that order.

- **JDBC (`JdbcClient`), not JPA.** The domain is immutable records and the ledger is append-only.
  Hibernate cannot map records, and JPA's dirty checking, lazy loading and caching bring nothing to
  a store that never updates. Plain SQL keeps `ledger-core` framework-free, makes every row loaded
  from the database pass through `Transaction`'s validating constructor, and gives direct access to
  Postgres features like `ON CONFLICT` and `SUM ... GROUP BY`.
- **Spring Data JDBC was considered.** It can build records through their constructor and fits the
  aggregate shape (`Transaction` owns its `Entry` list), but it needs persistence annotations on the
  domain, its `save()` treats a pre-assigned UUID as an update, and the critical queries would be
  hand-written `@Query` SQL anyway.
- **Flyway owns the schema.** Versioned migrations in `db/migration`; nothing is generated at runtime.
- **The database repeats the rules it can enforce.** `UNIQUE(idempotency_key)`, non-zero amounts and
  the currency format are also `CHECK`/`UNIQUE` constraints, so a manual fix in `psql` cannot break
  them. "Entries sum to zero" spans rows, so it is enforced by `Transaction` and by the fact that the
  transaction and its entries are written in a single database transaction.
- **Entries keep their order.** SQL rows have no inherent order, so each entry stores its `line_no`
  and `(transaction_id, line_no)` is its primary key.
- **Idempotent writes with `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING RETURNING id`.**
  The `UNIQUE` constraint is the guarantee; `ON CONFLICT` changes how a duplicate is reported.
  A plain insert would fail with a unique violation, which in Postgres aborts the whole database
  transaction (so the existing row cannot be read back), matches any unique constraint rather than
  just this one, and fills the error logs with what is normal traffic. A check-then-insert would race
  between worker pods. With `ON CONFLICT`, a duplicate returns no row; the existing transaction is
  loaded and compared: same entries → `200` with the original, different entries → `409 Conflict`.
- **Tests run against real Postgres (Testcontainers), not H2.** Unique-violation behaviour,
  `ON CONFLICT`, `timestamptz` and error codes are Postgres-specific, and an in-memory substitute
  would pass tests that fail in production. The image is pinned (`postgres:18`) to match the RDS major
  version, so the test database never changes underneath the build.
- **Balances are computed in SQL** (`SUM(amount) ... GROUP BY currency`, backed by an index on
  `(account_id, currency)`) rather than by loading every transaction; tests check the SQL result
  against `Balances.of`, which acts as the reference implementation.

### API (planned)

| Method | Path | Result |
|---|---|---|
| `POST` | `/transactions` | `201` new · `200` same key and entries · `409` same key, different entries · `422` invalid |
| `GET` | `/transactions/{id}` | the transaction, or `404` |
| `POST` | `/transactions/{id}/reversals` | the reversing transaction (body carries the new idempotency key) |
| `GET` | `/accounts/{accountId}/balances` | e.g. `{"INR": -40000}` |

Errors are returned as RFC 9457 `application/problem+json`.

## Running locally

Requirements: Java 25 and a running Docker daemon (the `ledger-api` tests start a Postgres
container). Maven is not needed; the wrapper downloads the pinned version.

```bash
./mvnw verify                  # build and test every module
./mvnw -pl ledger-core verify  # domain tests only, no Docker needed
```

## Security and supply chain

- GitHub secret scanning with push protection
- Dependabot version updates (Maven, GitHub Actions, Docker) and security updates
- CodeQL analysis (with the CI pipeline, planned)
