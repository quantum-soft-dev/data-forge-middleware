package com.bitbi.dfm.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reader behind the two #197 guards. Its job is to be unfoolable in one direction: a
 * connection-init statement that does <em>not</em> leave a session bounded must never read as one
 * that does, because the guard on the fast gate has nothing else to go on.
 */
@DisplayName("Reading the lock wait bound out of connection-init-sql (#197)")
class LockWaitBoundTest {

    @Test
    @DisplayName("a unit is applied, and a bare number is milliseconds as PostgreSQL reads it")
    void shouldReadTheDeclaredAmount() {
        assertThat(LockWaitBound.parseDeclared("SET lock_timeout = '10s'"))
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(LockWaitBound.parseDeclared("SET lock_timeout = '30s'"))
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(LockWaitBound.parseDeclared("SET lock_timeout TO 10000"))
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(LockWaitBound.parseDeclared("set LOCK_TIMEOUT = '2min'"))
                .isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("the last mention wins, because that is the one the session is left with")
    void shouldReadTheLastMention() {
        // pgjdbc sends the init SQL over the simple query protocol, so several statements in one
        // string are legal and are applied in order. Reading the first would pass this at 10 s.
        assertThat(LockWaitBound.parseDeclared("SET lock_timeout = '10s'; SET lock_timeout = 0"))
                .isEqualTo(Duration.ZERO);
        assertThat(LockWaitBound.parseDeclared("SET lock_timeout = '10s'; RESET lock_timeout"))
                .isEqualTo(Duration.ZERO);
        assertThat(LockWaitBound.parseDeclared("SET lock_timeout = '10s'; SET lock_timeout TO DEFAULT"))
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("the other two ways of undoing the bound are read as undoing it")
    void shouldReadTheUndoingSpellingsThatDoNotNameTheGucDirectly() {
        // RESET ALL never names lock_timeout, and set_config is the function spelling of SET —
        // both leave the session at the server default of 0 while a naive read of the statement
        // before them says 10 s.
        assertThat(LockWaitBound.parseDeclared("SET lock_timeout = '10s'; RESET ALL"))
                .isEqualTo(Duration.ZERO);
        assertThat(LockWaitBound.parseDeclared(
                "SET lock_timeout = '10s'; SELECT set_config('lock_timeout', '0', false)"))
                .isEqualTo(Duration.ZERO);
        assertThat(LockWaitBound.parseDeclared(
                "SELECT set_config('lock_timeout', '', false)"))
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("set_config is read as the bound it sets, not only as a way of clearing one")
    void shouldReadTheFunctionSpellingOfSet() {
        assertThat(LockWaitBound.parseDeclared(
                "SELECT set_config('lock_timeout', '10s', false)"))
                .isEqualTo(Duration.ofSeconds(10));
        assertThatThrownBy(() -> LockWaitBound.parseDeclared(
                "SELECT set_config('lock_timeout', 'ten seconds', false)"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ten seconds");
    }

    @Test
    @DisplayName("a unit PostgreSQL does not use fails instead of being read as milliseconds")
    void shouldRefuseAnUnknownUnit() {
        assertThatThrownBy(() -> LockWaitBound.parseDeclared("SET lock_timeout = '30000xyz'"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("xyz");
    }

    @Test
    @DisplayName("a real but tiny unit is read as what it is, so the range check can refuse it")
    void shouldReadMicroseconds() {
        Duration bound = LockWaitBound.parseDeclared("SET lock_timeout = '30000us'");

        assertThat(bound).isEqualTo(Duration.ofMillis(30));
        assertThatThrownBy(() -> LockWaitBound.assertBoundsALockWait("a probe", bound))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("below the PT15S");
    }

    @Test
    @DisplayName("a statement that never mentions the GUC is not a bound")
    void shouldRefuseAStatementThatSetsNothing() {
        assertThatThrownBy(() -> LockWaitBound.parseDeclared("SET application_name = 'dfm-test'"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("sets no lock_timeout");
    }

    @Test
    @DisplayName("a bound that only survives its own transaction is not a bound on the session")
    void shouldRefuseATransactionScopedAssignment() {
        // Hikari commits the init SQL, so SET LOCAL leaves a pooled session exactly as unbounded
        // as no statement at all — while reading, naively, as the 30 s it names.
        assertThat(LockWaitBound.parseDeclared("SET LOCAL lock_timeout = '30s'"))
                .isEqualTo(Duration.ZERO);
        assertThat(LockWaitBound.parseDeclared(
                "SELECT set_config('lock_timeout', '30s', true)"))
                .isEqualTo(Duration.ZERO);
        assertThat(LockWaitBound.parseDeclared("SET lock_timeout = '30s'; DISCARD ALL"))
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("an amount no duration can hold fails as this guard, not as a raw JDK exception")
    void shouldRefuseAnUnusableAmount() {
        assertThatThrownBy(() -> LockWaitBound.parseDeclared("SET lock_timeout = '99999999999999999999'"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("#197");
        assertThatThrownBy(() -> LockWaitBound.parseDeclared("SET lock_timeout = '9223372036854775807d'"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("#197");
    }

    @Test
    @DisplayName("zero is refused by name: it is the default this guard exists to replace")
    void shouldRefuseZero() {
        assertThatThrownBy(() ->
                LockWaitBound.assertBoundsALockWait("a probe", Duration.ZERO))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("wait for ever");
    }

    @Test
    @DisplayName("a bound long enough to approximate the hang it replaces is refused too")
    void shouldRefuseABoundAboveTheCeiling() {
        assertThatThrownBy(() ->
                LockWaitBound.assertBoundsALockWait("a probe", LockWaitBound.MAX.plusSeconds(1)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ceiling");
    }
}
