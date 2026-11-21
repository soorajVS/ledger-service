package io.github.soorajvs.ledger.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TransactionTest {

    @Test
    public void testBalancedTransaction(){
        Entry entryfrom = new Entry("provider:razorpay", +50000, Currency.getInstance("INR"));
        Entry entryto = new Entry("customer:42", -50000, Currency.getInstance("INR"));
        Transaction transaction = new Transaction(UUID.randomUUID(),
                "key1", List.of(entryfrom, entryto), Instant.now());
    }

    @Test
    public void testUnbalancedTransaction(){
        Entry entryfrom = new Entry("provider:razorpay", +50000, Currency.getInstance("INR"));
        Entry entryto = new Entry("customer:42", -40000, Currency.getInstance("INR"));
        InvalidTransactionException exception = assertThrows(
                InvalidTransactionException.class,
                () -> new Transaction(UUID.randomUUID(),
                        "idempotencyKey",
                        List.of(entryfrom, entryto), Instant.now())
        );
        assertEquals(InvalidTransactionException.Reason.UNBALANCED, exception.getReason());
    }

    @Test
    public void testfewEntries(){
        Entry entry = new Entry("customer:42", +50000, Currency.getInstance("INR"));
        InvalidTransactionException exception = assertThrows(
                InvalidTransactionException.class,
                () -> new Transaction(UUID.randomUUID(), "idempotencyKey", List.of(entry), Instant.now())
        );
        assertEquals(InvalidTransactionException.Reason.TOO_FEW_ENTRIES, exception.getReason());
    }

    @Test
    public void testZeroAmountTransaction(){
        Entry entryfrom = new Entry("provider:razorpay", +50000, Currency.getInstance("INR"));
        Entry entryto = new Entry("customer:42", -50000, Currency.getInstance("INR"));
        Entry entryZero = new Entry("customer:42", 0, Currency.getInstance("INR"));
        InvalidTransactionException exception = assertThrows(
                InvalidTransactionException.class,
                () -> new Transaction(UUID.randomUUID(),
                        "idempotencyKey",
                        List.of(entryfrom, entryto, entryZero), Instant.now())
        );
        assertEquals(InvalidTransactionException.Reason.ZERO_AMOUNT, exception.getReason());
    }

    @Test
    public void testMissingIdempotencyKey(){
        Entry entryfrom = new Entry("provider:razorpay", +50000, Currency.getInstance("INR"));
        Entry entryto = new Entry("customer:42", -50000, Currency.getInstance("INR"));
        InvalidTransactionException exception = assertThrows(
                InvalidTransactionException.class,
                () -> new Transaction(UUID.randomUUID(),
                        "",
                        List.of(entryfrom, entryto), Instant.now())
        );
        assertEquals(InvalidTransactionException.Reason.MISSING_IDEMPOTENCY_KEY, exception.getReason());
    }


    @Test
    public void testOverFlow(){
        Entry entryfrom = new Entry("provider:razorpay", Long.MAX_VALUE - 10, Currency.getInstance("INR"));
        Entry entryto = new Entry("customer:42", 11, Currency.getInstance("INR"));
        InvalidTransactionException exception = assertThrows(
                InvalidTransactionException.class,
                () -> new Transaction(UUID.randomUUID(),
                        "idempotencyKey",
                        List.of(entryfrom, entryto), Instant.now())
        );
        assertEquals(InvalidTransactionException.Reason.OVERFLOW, exception.getReason());
    }
}
