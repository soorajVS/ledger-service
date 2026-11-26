package io.github.soorajvs.ledger.core;

import java.util.*;
import java.util.stream.Collectors;

public final class Balances {

    private Balances() {

    }
    public static Map<Currency, Long> of(String accountId, Collection<Transaction> transactions){
        Objects.requireNonNull(accountId, "Account ID must not be null");
        Objects.requireNonNull(transactions, "Transactions must not be null");
        Map<Currency, Long> currencyBalance = new HashMap<>();
        transactions.stream()
                .flatMap(transaction -> transaction.entries().stream())
                .filter(entry -> entry.accountId().equals(accountId))
                .forEach(entry -> currencyBalance.merge(entry.currency(), entry.amount(), Math::addExact));
        return Collections.unmodifiableMap(currencyBalance);
    }


}
