package com.bitbi.dfm.config;

import com.bitbi.dfm.testsupport.LockWaitBound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Issue #197 — under the {@code test} profile every pooled connection must carry a
 * {@code lock_timeout}, so a statement blocked on a lock fails instead of hanging the run.
 *
 * <p>The suite shares one PostgreSQL database across every cached Spring context, and each context
 * keeps its background workers alive for the rest of the run. A statement of the class under test
 * can therefore wait on a row lock a sibling context holds. PostgreSQL's default
 * {@code lock_timeout} is 0 — wait for ever — so that wait never ends: the run stops rather than
 * fails, and CI kills the job without naming the test that was stuck.</p>
 *
 * <p>This is the static half of the guard, on the fast per-task gate: it fails the day
 * {@code spring.datasource.hikari.connection-init-sql} is dropped from
 * {@code application-test.yml} or stops setting a sane bound. The wired half is
 * {@code DatabaseLockWaitBoundIntegrationTest}, which reads what a real pooled connection carries
 * and proves a blocked statement really is aborted — the two things a file cannot show.</p>
 *
 * <p>It lives beside {@link ParquetScratchTestProfileTest} because its subject is the {@code test}
 * profile itself, and it reads the profile through the same package-private YAML helper rather
 * than growing a second parser that could disagree with it.</p>
 */
@DisplayName("Lock wait bound under the test profile (#197)")
class LockWaitBoundTestProfileTest {

    private static final String INIT_SQL_KEY = "spring.datasource.hikari.connection-init-sql";

    @Test
    @DisplayName("every pooled connection is initialized with a lock_timeout")
    void shouldDeclareALockTimeoutOnConnectionInit() {
        Object declared = testYaml().get(INIT_SQL_KEY);

        assertNotNull(declared,
                INIT_SQL_KEY + " is not declared in application-test.yml, so pooled connections keep "
                        + "PostgreSQL's default lock_timeout of 0 and a statement blocked on a lock "
                        + "held by another cached context waits for ever (#197)");
        LockWaitBound.parseDeclared(declared.toString());
    }

    @Test
    @DisplayName("the bound clears the suite's own deliberate lock waits and still fails fast")
    void shouldBoundTheWaitWithinTheAgreedRange() {
        Duration declared = LockWaitBound.parseDeclared(String.valueOf(testYaml().get(INIT_SQL_KEY)));

        LockWaitBound.assertBoundsALockWait(INIT_SQL_KEY, declared);
    }

    private static Map<String, Object> testYaml() {
        Map<String, Object> yaml = ScheduledTaskInventoryTest.optionalYaml("application-test.yml");
        assertNotNull(yaml, "application-test.yml must be on the classpath");
        return yaml;
    }
}
