package io.github.soorajvs.ledger.core;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class TransactionTest {

    private static final Currency INR = Currency.getInstance("INR");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Clock T1 = Clock.fixed(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC);
    private static final Clock T2 = Clock.fixed(Instant.parse("2026-03-12T15:30:00Z"), ZoneOffset.UTC);

    @Test
    public void testBalancedTransaction(){
        Entry entryfrom = new Entry("provider:razorpay", +50000, INR);
        Entry entryto = new Entry("customer:42", -50000, INR);
        Transaction transaction = new Transaction(UUID.randomUUID(),
                "key1", List.of(entryfrom, entryto), Instant.now());
        assertEquals(2, transaction.entries().size());
        assertEquals(entryfrom, transaction.entries().get(0));
        assertEquals(entryto, transaction.entries().get(1));
    }

    @Test
    public void testUnbalancedTransaction(){
        Entry entryfrom = new Entry("provider:razorpay", +50000, INR);
        Entry entryto = new Entry("customer:42", -40000, INR);
        InvalidTransactionException exception = assertThrows(
                InvalidTransactionException.class,
                () -> new Transaction(UUID.randomUUID(),
                        "idempotencyKey",
                        List.of(entryfrom, entryto), Instant.now())
        );
        assertEquals(InvalidTransactionException.Reason.UNBALANCED, exception.getReason());
    }

    @Test
    public void testMixedCurrencyTransaction(){
        Entry entryfrom = new Entry("provider:razorpay", +50000, INR);
        Entry entryto = new Entry("customer:42", -50000, USD);
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
        Entry entry = new Entry("customer:42", +50000, INR);
        InvalidTransactionException exception = assertThrows(
                InvalidTransactionException.class,
                () -> new Transaction(UUID.randomUUID(), "idempotencyKey", List.of(entry), Instant.now())
        );
        assertEquals(InvalidTransactionException.Reason.TOO_FEW_ENTRIES, exception.getReason());
    }

    @Test
    public void testZeroAmountTransaction(){
        Entry entryfrom = new Entry("provider:razorpay", +50000, INR);
        Entry entryto = new Entry("customer:42", -50000, INR);
        Entry entryZero = new Entry("customer:42", 0, INR);
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
        Entry entryfrom = new Entry("provider:razorpay", +50000, INR);
        Entry entryto = new Entry("customer:42", -50000, INR);
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
        Entry entryfrom = new Entry("provider:razorpay", Long.MAX_VALUE - 10, INR);
        Entry entryto = new Entry("customer:42", 11, INR);
        InvalidTransactionException exception = assertThrows(
                InvalidTransactionException.class,
                () -> new Transaction(UUID.randomUUID(),
                        "idempotencyKey",
                        List.of(entryfrom, entryto), Instant.now())
        );
        assertEquals(InvalidTransactionException.Reason.OVERFLOW, exception.getReason());
    }

    private static List<Entry> paymentEntries() {
        return List.of(
                new Entry("provider:razorpay", 50000, INR),
                new Entry("customer:42", -50000, INR));
    }

    @Test
    void createPopulatesAllFields() {
        List<Entry> entries = paymentEntries();

        Transaction tx = Transaction.create("evt_abc123", entries, T1);

        assertNotNull(tx.id());
        assertEquals("evt_abc123", tx.idempotencyKey());
        assertEquals(entries, tx.entries());          // same entries, same order
        assertEquals(T1.instant(), tx.createdAt());   // timestamp comes from the injected clock
    }

    @Test
    void createGeneratesUniqueIdEachCall() {
        Transaction a = Transaction.create("evt_1", paymentEntries(), T1);
        Transaction b = Transaction.create("evt_1", paymentEntries(), T1);

        assertNotEquals(a.id(), b.id());
    }

    @Test
    void createDefensivelyCopiesEntries() {
        List<Entry> mutable = new ArrayList<>(paymentEntries());

        Transaction tx = Transaction.create("evt_abc123", mutable, T1);
        mutable.add(new Entry("customer:99", 1, INR));

        assertEquals(2, tx.entries().size());
        assertThrows(UnsupportedOperationException.class,
                () -> tx.entries().add(new Entry("customer:99", 1, INR)));
    }

    @Test
    void createStillEnforcesInvariants() {
        List<Entry> unbalanced = List.of(
                new Entry("provider:razorpay", 50000, INR),
                new Entry("customer:42", -40000, INR));

        InvalidTransactionException ex = assertThrows(InvalidTransactionException.class,
                () -> Transaction.create("evt_abc123", unbalanced, T1));
        assertEquals(InvalidTransactionException.Reason.UNBALANCED, ex.getReason());
    }

    @Test
    void createRejectsBlankIdempotencyKey() {
        InvalidTransactionException ex = assertThrows(InvalidTransactionException.class,
                () -> Transaction.create("  ", paymentEntries(), T1));
        assertEquals(InvalidTransactionException.Reason.MISSING_IDEMPOTENCY_KEY, ex.getReason());
    }

    // ---------- reverse() ----------

    @Test
    void reverseNegatesEveryEntryKeepingAccountCurrencyAndOrder() {
        Transaction original = Transaction.create("evt_abc123", paymentEntries(), T1);

        Transaction refund = original.reverse("evt_refund_789", T2);

        assertEquals(List.of(
                new Entry("provider:razorpay", -50000, INR),
                new Entry("customer:42", 50000, INR)), refund.entries());
    }

    @Test
    void reverseIsANewTransactionWithItsOwnIdKeyAndTimestamp() {
        Transaction original = Transaction.create("evt_abc123", paymentEntries(), T1);

        Transaction refund = original.reverse("evt_refund_789", T2);

        assertNotEquals(original.id(), refund.id());
        assertEquals("evt_refund_789", refund.idempotencyKey());
        assertEquals(T2.instant(), refund.createdAt());
    }

    @Test
    void reverseDoesNotMutateOriginal() {
        Transaction original = Transaction.create("evt_abc123", paymentEntries(), T1);

        original.reverse("evt_refund_789", T2);

        assertEquals(paymentEntries(), original.entries());
        assertEquals("evt_abc123", original.idempotencyKey());
        assertEquals(T1.instant(), original.createdAt());
    }

    @Test
    void reversingTwiceRestoresOriginalEntries() {
        Transaction original = Transaction.create("evt_abc123", paymentEntries(), T1);

        Transaction twice = original.reverse("evt_r1", T2).reverse("evt_r2", T2);

        assertEquals(original.entries(), twice.entries());
    }

    @Test
    void reverseHandlesMultiCurrencyTransactions() {
        List<Entry> fx = List.of(
                new Entry("customer:42", -10000, INR),
                new Entry("fx:pool:inr", 10000, INR),
                new Entry("fx:pool:usd", -120, USD),
                new Entry("customer:42:usd", 120, USD));
        Transaction original = Transaction.create("evt_fx_1", fx, T1);

        Transaction reversed = original.reverse("evt_fx_1_rev", T2);

        for (int i = 0; i < fx.size(); i++) {
            Entry before = original.entries().get(i);
            Entry after = reversed.entries().get(i);
            assertEquals(before.accountId(), after.accountId());
            assertEquals(before.currency(), after.currency());
            assertEquals(-before.amount(), after.amount());
        }
    }

    @Test
    void reverseRejectsBlankIdempotencyKey() {
        Transaction original = Transaction.create("evt_abc123", paymentEntries(), T1);

        InvalidTransactionException ex = assertThrows(InvalidTransactionException.class,
                () -> original.reverse("", T2));
        assertEquals(InvalidTransactionException.Reason.MISSING_IDEMPOTENCY_KEY, ex.getReason());
    }

    @Test
    void reverseOfEntryAtLongMinValueReportsOverflow() {
        // Valid: MIN + MAX + 1 == 0 with no intermediate overflow.
        // But -Long.MIN_VALUE is not representable, so the reversal cannot exist.
        List<Entry> edge = List.of(
                new Entry("a", Long.MIN_VALUE, INR),
                new Entry("b", Long.MAX_VALUE, INR),
                new Entry("c", 1, INR));
        Transaction original = Transaction.create("evt_edge", edge, T1);

        InvalidTransactionException ex = assertThrows(InvalidTransactionException.class,
                () -> original.reverse("evt_edge_rev", T2));
        assertEquals(InvalidTransactionException.Reason.OVERFLOW, ex.getReason());
    }
}
