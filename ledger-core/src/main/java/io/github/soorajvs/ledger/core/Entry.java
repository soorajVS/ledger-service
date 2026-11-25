package io.github.soorajvs.ledger.core;

import java.util.Currency;

public record Entry(String accountId, long amount, Currency currency) {
    public Entry{
        if(accountId == null || accountId.isBlank()){
            throw new IllegalArgumentException("Account Id cannot be empty");
        }
        if(currency == null){
            throw new IllegalArgumentException("currency cannot be null");
        }
    }
}
