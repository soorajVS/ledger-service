package io.github.soorajvs.ledger.core;

import java.util.Currency;

public record Entry(String accountId, long amount, Currency currency) {
}
