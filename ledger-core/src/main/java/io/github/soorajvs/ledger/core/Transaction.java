package io.github.soorajvs.ledger.core;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Transaction(UUID id, String idempotencyKey, List<Entry> entries, Instant createdAt) {
}
