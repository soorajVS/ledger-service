package io.github.soorajvs.ledger.core;

import java.util.*;

public final class Balances {

    private Balances() {

    }
    public static Map<Currency, Long> of(String accountId, Collection<Transaction> transactions){
        Objects.requireNonNull(accountId, "Account ID must not be null");
        Objects.requireNonNull(transactions, "Transactions must not be null");
        Map<Currency, Long> currencyBalance = new HashMap<>();
        List<Entry> accountEntries = transactions.stream()
                .flatMap(transaction -> transaction.entries().stream())
                .filter(entry -> entry.accountId().equals(accountId)).toList();
        for(Entry entry : accountEntries){
            currencyBalance.merge(entry.currency(), entry.amount(), Math::addExact);
        }
        return Collections.unmodifiableMap(currencyBalance);
    }


}
