package io.github.soorajvs.ledger.core;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class BalancesTest {
    private static final Currency INR = Currency.getInstance("INR");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Clock T1 = Clock.fixed(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC);
    private static final Clock T2 = Clock.fixed(Instant.parse("2026-03-12T15:30:00Z"), ZoneOffset.UTC);

    private static Transaction tx(String key, Clock clock, Entry... entries) {
        return Transaction.create(key, List.of(entries), clock);
    }

    @Test
    public void shouldGetBalance() {
        Transaction tx1 = tx("evt_abc123", T1,
                new Entry("razorpay:receivable", +50000, INR),
                new Entry("customer:42:wallet", -50000, INR));
        Transaction tx2 = tx("evt_abc456", T2,
                new Entry("razorpay:receivable", +20000, INR),
                new Entry("customer:42:wallet", -20000, INR));
        Transaction tx3 = tx("evt_abc789", T2,
                new Entry("revenue:subscriptions", -30000, INR),
                new Entry("customer:42:wallet", +30000, INR));

        Map<Currency, Long> currencyBalance = Balances.of("customer:42:wallet", List.of(tx1, tx2, tx3));
        assertEquals(-40000, currencyBalance.get(INR));
        currencyBalance = Balances.of("revenue:subscriptions", List.of(tx1, tx2, tx3));
        assertEquals(-30000, currencyBalance.get(INR));
        currencyBalance = Balances.of("unknown:account", List.of(tx1, tx2, tx3));
        assertTrue(currencyBalance.isEmpty());
    }

    @Test
    public void shouldBalanceTransaction() {
        Transaction tx1 = tx("evt_abc123", T1,
                new Entry("razorpay:receivable", +50000, INR),
                new Entry("customer:42:wallet", -50000, INR));
        Transaction tx2 = tx("evt_abc789", T2,
                new Entry("revenue:subscriptions", -50000, INR),
                new Entry("customer:42:wallet", +50000, INR));
        Transaction tx3 = tx("evt_abc999", T2,
                new Entry("razorpay:receivable", -50000, INR),
                new Entry("revenue:subscriptions", +50000, INR));

        Map<Currency, Long> currencyBalance = Balances.of("customer:42:wallet", List.of(tx1, tx2, tx3));
        assertEquals(0L, currencyBalance.get(INR));
        currencyBalance = Balances.of("razorpay:receivable", List.of(tx1, tx2, tx3));
        assertEquals(0L, currencyBalance.get(INR));
        currencyBalance = Balances.of("revenue:subscriptions", List.of(tx1, tx2, tx3));
        assertEquals(0L, currencyBalance.get(INR));
    }

    @Test
    public void balancesAcrossAllAccountsSumToZero() {
        // Individual accounts are non-zero, but double-entry guarantees the whole ledger nets to zero.
        List<Transaction> ledger = List.of(
                tx("evt_topup_1", T1,
                        new Entry("razorpay:receivable", +50000, INR),
                        new Entry("customer:42:wallet", -50000, INR)),
                tx("evt_topup_2", T2,
                        new Entry("razorpay:receivable", +20000, INR),
                        new Entry("customer:42:wallet", -20000, INR)),
                tx("evt_spend_1", T2,
                        new Entry("customer:42:wallet", +30000, INR),
                        new Entry("revenue:subscriptions", -30000, INR)),
                tx("evt_usd_1", T2,
                        new Entry("stripe:receivable", +1200, USD),
                        new Entry("customer:42:wallet", -1200, USD)));

        Set<String> accounts = new HashSet<>();
        ledger.forEach(t -> t.entries().forEach(e -> accounts.add(e.accountId())));

        Map<Currency, Long> total = new HashMap<>();
        for (String account : accounts) {
            Balances.of(account, ledger).forEach((currency, amount) -> total.merge(currency, amount, Math::addExact));
        }

        assertNotEquals(0L, Balances.of("customer:42:wallet", ledger).get(INR));
        assertEquals(Map.of(INR, 0L, USD, 0L), total);
    }

    @Test
    public void shouldThrowNullPointerException() {
        assertThrows(NullPointerException.class, () -> Balances.of("customer:42:wallet", null));
    }

    @Test
    public void shouldThrowNullPointerExceptionforAccount() {
        Transaction tx1 = tx("evt_abc123", T1,
                new Entry("razorpay:receivable", +50000, INR),
                new Entry("customer:42:wallet", -50000, INR));
        assertThrows(NullPointerException.class, () -> Balances.of(null, List.of(tx1)));
    }


    @Test
    public void unknownAccountReturnsEmptyMapNotNull() {
        Transaction t = tx("evt_1", T1,
                new Entry("razorpay:receivable", 50000, INR),
                new Entry("customer:42:wallet", -50000, INR));

        Map<Currency, Long> balance = Balances.of("unknown:account", List.of(t));

        assertNotNull(balance);
        assertTrue(balance.isEmpty());
    }

    @Test
    public void noTransactionsReturnsEmptyMap() {
        assertTrue(Balances.of("customer:42:wallet", List.of()).isEmpty());
    }

    @Test
    public void balancesAreKeptSeparatePerCurrency() {
        Transaction inr = tx("evt_inr", T1,
                new Entry("razorpay:receivable", 50000, INR),
                new Entry("customer:42:wallet", -50000, INR));
        Transaction usd = tx("evt_usd", T2,
                new Entry("stripe:receivable", 1200, USD),
                new Entry("customer:42:wallet", -1200, USD));

        Map<Currency, Long> balance = Balances.of("customer:42:wallet", List.of(inr, usd));

        assertEquals(Map.of(INR, -50000L, USD, -1200L), balance);
    }

    @Test
    public void reversedTransactionNetsToZero() {
        Transaction original = tx("evt_1", T1,
                new Entry("razorpay:receivable", 50000, INR),
                new Entry("customer:42:wallet", -50000, INR));
        Transaction refund = original.reverse("evt_1_refund", T2);

        Map<Currency, Long> balance = Balances.of("customer:42:wallet", List.of(original, refund));

        // Account has activity in INR, so the key is present with a zero balance.
        assertEquals(Map.of(INR, 0L), balance);
    }

    @Test
    public void accountIdMustMatchExactly() {
        Transaction t = tx("evt_1", T1,
                new Entry("customer:4", 100, INR),
                new Entry("customer:42", -100, INR));

        assertEquals(Map.of(INR, 100L), Balances.of("customer:4", List.of(t)));
        assertEquals(Map.of(INR, -100L), Balances.of("customer:42", List.of(t)));
        assertTrue(Balances.of("CUSTOMER:42", List.of(t)).isEmpty());
    }

    @Test
    public void multipleEntriesForSameAccountInOneTransactionAreSummed() {
        // Split payment: wallet is debited twice inside a single transaction.
        Transaction t = tx("evt_split", T1,
                new Entry("customer:42:wallet", 30000, INR),
                new Entry("customer:42:wallet", 20000, INR),
                new Entry("revenue:subscriptions", -50000, INR));

        assertEquals(Map.of(INR, 50000L), Balances.of("customer:42:wallet", List.of(t)));
    }

    @Test
    public void resultDoesNotDependOnTransactionOrder() {
        Transaction a = tx("evt_a", T1,
                new Entry("razorpay:receivable", 50000, INR),
                new Entry("customer:42:wallet", -50000, INR));
        Transaction b = tx("evt_b", T2,
                new Entry("customer:42:wallet", 30000, INR),
                new Entry("revenue:subscriptions", -30000, INR));

        assertEquals(
                Balances.of("customer:42:wallet", List.of(a, b)),
                Balances.of("customer:42:wallet", List.of(b, a)));
    }

    @Test
    public void acceptsAnyCollectionNotJustList() {
        Transaction t = tx("evt_1", T1,
                new Entry("razorpay:receivable", 50000, INR),
                new Entry("customer:42:wallet", -50000, INR));

        assertEquals(Map.of(INR, -50000L), Balances.of("customer:42:wallet", Set.of(t)));
    }

    @Test
    public void balanceOverflowIsReportedNotSilentlyTruncated() {
        // Each transaction is individually valid, but the running balance of "a" exceeds Long.MAX_VALUE.
        Transaction big = tx("evt_big", T1,
                new Entry("a", Long.MAX_VALUE, INR),
                new Entry("b", -Long.MAX_VALUE, INR));
        Transaction one = tx("evt_one", T2,
                new Entry("a", 1, INR),
                new Entry("b", -1, INR));

        assertThrows(ArithmeticException.class,
                () -> Balances.of("a", List.of(big, one)));
    }

    @Test
    public void returnedMapIsUnmodifiable() {
        Transaction t = tx("evt_1", T1,
                new Entry("razorpay:receivable", 50000, INR),
                new Entry("customer:42:wallet", -50000, INR));

        Map<Currency, Long> balance = Balances.of("customer:42:wallet", List.of(t));

        assertThrows(UnsupportedOperationException.class, () -> balance.put(USD, 1L));
    }
}
