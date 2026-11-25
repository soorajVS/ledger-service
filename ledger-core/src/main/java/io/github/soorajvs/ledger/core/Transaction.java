package io.github.soorajvs.ledger.core;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public record Transaction(UUID id, String idempotencyKey, List<Entry> entries, Instant createdAt) {
    public Transaction {
        Objects.requireNonNull(id, "Account Id cannot be null");
        Objects.requireNonNull(createdAt, "Created At cannot be null");

        if(idempotencyKey == null || idempotencyKey.isBlank()){
            throw new InvalidTransactionException(InvalidTransactionException.Reason.MISSING_IDEMPOTENCY_KEY);
        }
        if(entries == null || entries.size() < 2){
            throw new InvalidTransactionException(InvalidTransactionException.Reason.TOO_FEW_ENTRIES);
        }
        if (entries.stream().anyMatch(entry -> entry.amount() == 0)){
            throw new InvalidTransactionException(InvalidTransactionException.Reason.ZERO_AMOUNT);
        }

        entries = List.copyOf(entries);

        Map<Currency, List<Entry>> currencyEntryMap = entries.stream()
                .collect(Collectors.groupingBy(Entry::currency));

        for(Map.Entry<Currency, List<Entry>> entry : currencyEntryMap.entrySet()){
            List<Entry> list = entry.getValue();
            long sum = 0L;
            for(Entry entry1 : list){
                try {
                    sum = Math.addExact(sum, entry1.amount());
                }catch (ArithmeticException e){
                    throw new InvalidTransactionException(InvalidTransactionException.Reason.OVERFLOW);
                }
            }
            if(sum != 0){
                throw new InvalidTransactionException(InvalidTransactionException.Reason.UNBALANCED);
            }
        }
    }


    public static Transaction create(String idempotenceKey, List<Entry> entries, Clock clock){
        return new Transaction(UUID.randomUUID(), idempotenceKey, entries, clock.instant());
    }

    public Transaction reverse(String idempotenceKey,Clock clock){
        List<Entry> reverseEntries = entries().stream()
                .map(entry -> new Entry(entry.accountId(),  Math.negateExact(entry.amount()), entry.currency())).toList();
        return create(idempotenceKey, reverseEntries, clock);
    }
}
