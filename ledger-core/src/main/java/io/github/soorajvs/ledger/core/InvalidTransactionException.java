package io.github.soorajvs.ledger.core;

public class InvalidTransactionException extends RuntimeException {
    private final Reason reason;
    public InvalidTransactionException(Reason reason){
        this.reason = reason;
        super(reason.name());
    }

    public Reason getReason(){
        return reason;
    }

    public enum Reason {
        UNBALANCED,
        TOO_FEW_ENTRIES,
        ZERO_AMOUNT,
        MISSING_IDEMPOTENCY_KEY,
        OVERFLOW
    }
}
