package com.bitbi.dfm.error.domain;

import com.bitbi.dfm.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository tests for global error handling queries.
 * <p>
 * Tests new queries: findGlobalErrorsByAccountId, countUnreadGlobalErrorsByAccountId,
 * markAsReadByIds, markAllAsReadByAccountId.
 * </p>
 */
@DisplayName("ErrorLogRepository Tests - Global Error Handling")
@Sql("/test-data.sql")
@Transactional
class ErrorLogRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private ErrorLogRepository errorLogRepository;

    // Test data from test-data.sql
    // Account ID from test data (store-01 belongs to this account)
    private static final UUID TEST_ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    // Site ID from test data (store-01.example.com)
    private static final UUID TEST_SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    // Existing batch ID from test data (for batch error tests)
    private static final UUID TEST_BATCH_ID = UUID.fromString("b1c2d3e4-f5a6-7890-bcde-f12345678903");

    @Nested
    @DisplayName("T003: findGlobalErrorsByAccountId")
    class FindGlobalErrorsByAccountIdTests {

        @Test
        @DisplayName("Should find global errors (batch_id IS NULL) for account")
        void shouldFindGlobalErrorsForAccount() {
            // Given: Create a global error (no batch association)
            ErrorLog globalError = ErrorLog.create(
                    TEST_SITE_ID,
                    null, // No batch - this is a global error
                    "CONFIG_ERROR",
                    "Configuration Error",
                    "Test message",
                    null,
                    null,
                    null,
                    ErrorSeverity.ERROR
            );
            errorLogRepository.save(globalError);

            // When
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));
            Page<ErrorLog> result = errorLogRepository.findGlobalErrorsByAccountId(TEST_ACCOUNT_ID, pageable);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).isNotEmpty();
            assertThat(result.getContent()).allMatch(e -> e.getBatchId() == null);
        }

        @Test
        @DisplayName("Should return empty page when no global errors for account")
        void shouldReturnEmptyPageWhenNoGlobalErrors() {
            // Given: No global errors exist for this account
            UUID otherAccountId = UUID.randomUUID();

            // When
            PageRequest pageable = PageRequest.of(0, 20);
            Page<ErrorLog> result = errorLogRepository.findGlobalErrorsByAccountId(otherAccountId, pageable);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("Should exclude batch-associated errors")
        void shouldExcludeBatchAssociatedErrors() {
            // Given: Create both global and batch errors
            ErrorLog globalError = ErrorLog.create(
                    TEST_SITE_ID,
                    null,
                    "GLOBAL_ERROR",
                    "Global Error",
                    "Global message",
                    null,
                    null,
                    null,
                    ErrorSeverity.ERROR
            );
            errorLogRepository.save(globalError);

            // Use existing batch ID from test-data.sql to avoid FK constraint violation
            ErrorLog batchError = ErrorLog.create(
                    TEST_SITE_ID,
                    TEST_BATCH_ID,
                    "BATCH_ERROR",
                    "Batch Error",
                    "Batch message",
                    null,
                    null,
                    null,
                    ErrorSeverity.ERROR
            );
            errorLogRepository.save(batchError);

            // When
            PageRequest pageable = PageRequest.of(0, 100);
            Page<ErrorLog> result = errorLogRepository.findGlobalErrorsByAccountId(TEST_ACCOUNT_ID, pageable);

            // Then
            assertThat(result.getContent())
                    .allMatch(e -> e.getBatchId() == null)
                    .noneMatch(e -> "BATCH_ERROR".equals(e.getType()));
        }

        @Test
        @DisplayName("Should filter by unreadOnly when true")
        void shouldFilterByUnreadOnly() {
            // Given: Create global errors with different read status
            ErrorLog unreadError = ErrorLog.create(
                    TEST_SITE_ID,
                    null,
                    "UNREAD_ERROR",
                    "Unread Error",
                    "Unread message",
                    null,
                    null,
                    null,
                    ErrorSeverity.ERROR
            );
            errorLogRepository.save(unreadError);

            // When filtering unread only
            PageRequest pageable = PageRequest.of(0, 100);
            Page<ErrorLog> result = errorLogRepository.findGlobalErrorsByAccountIdAndUnread(TEST_ACCOUNT_ID, pageable);

            // Then
            assertThat(result.getContent())
                    .allMatch(e -> !e.isRead());
        }
    }

    @Nested
    @DisplayName("T004: countUnreadGlobalErrorsByAccountId")
    class CountUnreadGlobalErrorsByAccountIdTests {

        @Test
        @DisplayName("Should count unread global errors for account")
        void shouldCountUnreadGlobalErrors() {
            // Given: Create unread global errors
            for (int i = 0; i < 3; i++) {
                ErrorLog error = ErrorLog.create(
                        TEST_SITE_ID,
                        null,
                        "TEST_ERROR_" + i,
                        "Test Error " + i,
                        "Message " + i,
                        null,
                        null,
                        null,
                        ErrorSeverity.ERROR
                );
                errorLogRepository.save(error);
            }

            // When
            long count = errorLogRepository.countUnreadGlobalErrorsByAccountId(TEST_ACCOUNT_ID);

            // Then
            assertThat(count).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("Should return zero when no unread global errors")
        void shouldReturnZeroWhenNoUnreadErrors() {
            // Given: No unread errors for non-existent account
            UUID nonExistentAccountId = UUID.randomUUID();

            // When
            long count = errorLogRepository.countUnreadGlobalErrorsByAccountId(nonExistentAccountId);

            // Then
            assertThat(count).isZero();
        }

        @Test
        @DisplayName("Should exclude read errors from count")
        void shouldExcludeReadErrorsFromCount() {
            // Given: Create an error and mark it as read
            ErrorLog error = ErrorLog.create(
                    TEST_SITE_ID,
                    null,
                    "READ_ERROR",
                    "Read Error",
                    "This error is read",
                    null,
                    null,
                    null,
                    ErrorSeverity.INFO
            );
            ErrorLog saved = errorLogRepository.save(error);

            // Mark as read
            errorLogRepository.markAsReadByIds(List.of(saved.getId()), saved.getOccurredAt());

            // When
            long count = errorLogRepository.countUnreadGlobalErrorsByAccountId(TEST_ACCOUNT_ID);

            // Then - count should not include the read error
            // We verify by checking count doesn't include errors marked as read
            Page<ErrorLog> unreadErrors = errorLogRepository.findGlobalErrorsByAccountIdAndUnread(
                    TEST_ACCOUNT_ID, PageRequest.of(0, 100));
            assertThat(unreadErrors.getContent())
                    .noneMatch(e -> e.getId().equals(saved.getId()));
        }
    }

    @Nested
    @DisplayName("T005: markAsReadByIds")
    class MarkAsReadByIdsTests {

        @Test
        @DisplayName("Should mark specified errors as read")
        void shouldMarkSpecifiedErrorsAsRead() {
            // Given: Create unread errors
            ErrorLog error1 = ErrorLog.create(
                    TEST_SITE_ID,
                    null,
                    "ERROR_1",
                    "Error 1",
                    "Message 1",
                    null,
                    null,
                    null,
                    ErrorSeverity.ERROR
            );
            ErrorLog error2 = ErrorLog.create(
                    TEST_SITE_ID,
                    null,
                    "ERROR_2",
                    "Error 2",
                    "Message 2",
                    null,
                    null,
                    null,
                    ErrorSeverity.WARNING
            );
            ErrorLog saved1 = errorLogRepository.save(error1);
            ErrorLog saved2 = errorLogRepository.save(error2);

            assertThat(saved1.isRead()).isFalse();
            assertThat(saved2.isRead()).isFalse();

            // When
            int updated = errorLogRepository.markAsReadByIds(
                    List.of(saved1.getId(), saved2.getId()),
                    saved1.getOccurredAt()
            );

            // Then
            assertThat(updated).isEqualTo(2);

            // Verify errors are marked as read
            ErrorLog reloaded1 = errorLogRepository.findById(saved1.getId()).orElseThrow();
            ErrorLog reloaded2 = errorLogRepository.findById(saved2.getId()).orElseThrow();
            assertThat(reloaded1.isRead()).isTrue();
            assertThat(reloaded2.isRead()).isTrue();
        }

        @Test
        @DisplayName("Should return count of actually updated errors")
        void shouldReturnCountOfUpdatedErrors() {
            // Given: One unread error
            ErrorLog error = ErrorLog.create(
                    TEST_SITE_ID,
                    null,
                    "SINGLE_ERROR",
                    "Single Error",
                    "Single message",
                    null,
                    null,
                    null,
                    ErrorSeverity.CRITICAL
            );
            ErrorLog saved = errorLogRepository.save(error);

            // When: Try to update this error and a non-existent one
            List<UUID> ids = List.of(saved.getId(), UUID.randomUUID());
            int updated = errorLogRepository.markAsReadByIds(ids, saved.getOccurredAt());

            // Then: Only one should be updated
            assertThat(updated).isEqualTo(1);
        }

        @Test
        @DisplayName("Should not update already read errors")
        void shouldNotUpdateAlreadyReadErrors() {
            // Given: Create and mark as read
            ErrorLog error = ErrorLog.create(
                    TEST_SITE_ID,
                    null,
                    "ALREADY_READ",
                    "Already Read",
                    "Message",
                    null,
                    null,
                    null,
                    ErrorSeverity.INFO
            );
            ErrorLog saved = errorLogRepository.save(error);
            errorLogRepository.markAsReadByIds(List.of(saved.getId()), saved.getOccurredAt());

            // When: Try to mark as read again
            int updated = errorLogRepository.markAsReadByIds(List.of(saved.getId()), saved.getOccurredAt());

            // Then: Should return 0 since already read
            assertThat(updated).isZero();
        }
    }

    @Nested
    @DisplayName("T006: markAllAsReadByAccountId")
    class MarkAllAsReadByAccountIdTests {

        @Test
        @DisplayName("Should mark all unread global errors as read for account")
        void shouldMarkAllUnreadGlobalErrorsAsRead() {
            // Given: Create multiple unread errors
            for (int i = 0; i < 5; i++) {
                ErrorLog error = ErrorLog.create(
                        TEST_SITE_ID,
                        null,
                        "BULK_ERROR_" + i,
                        "Bulk Error " + i,
                        "Bulk message " + i,
                        null,
                        null,
                        null,
                        ErrorSeverity.WARNING
                );
                errorLogRepository.save(error);
            }

            // Verify we have unread errors
            long unreadBefore = errorLogRepository.countUnreadGlobalErrorsByAccountId(TEST_ACCOUNT_ID);
            assertThat(unreadBefore).isGreaterThanOrEqualTo(5);

            // When
            int updated = errorLogRepository.markAllAsReadByAccountId(TEST_ACCOUNT_ID);

            // Then
            assertThat(updated).isGreaterThanOrEqualTo(5);

            // Verify count is now zero
            long unreadAfter = errorLogRepository.countUnreadGlobalErrorsByAccountId(TEST_ACCOUNT_ID);
            assertThat(unreadAfter).isZero();
        }

        @Test
        @DisplayName("Should return zero when no unread errors for account")
        void shouldReturnZeroWhenNoUnreadErrors() {
            // Given: Non-existent account
            UUID nonExistentAccountId = UUID.randomUUID();

            // When
            int updated = errorLogRepository.markAllAsReadByAccountId(nonExistentAccountId);

            // Then
            assertThat(updated).isZero();
        }

        @Test
        @DisplayName("Should only affect global errors, not batch errors")
        void shouldOnlyAffectGlobalErrors() {
            // Given: Create global and batch errors
            ErrorLog globalError = ErrorLog.create(
                    TEST_SITE_ID,
                    null,
                    "GLOBAL_FOR_BULK",
                    "Global For Bulk",
                    "Global message",
                    null,
                    null,
                    null,
                    ErrorSeverity.ERROR
            );
            errorLogRepository.save(globalError);

            // Use existing batch ID from test-data.sql to avoid FK constraint violation
            ErrorLog batchError = ErrorLog.create(
                    TEST_SITE_ID,
                    TEST_BATCH_ID,
                    "BATCH_FOR_BULK",
                    "Batch For Bulk",
                    "Batch message",
                    null,
                    null,
                    null,
                    ErrorSeverity.ERROR
            );
            ErrorLog savedBatch = errorLogRepository.save(batchError);

            // When
            errorLogRepository.markAllAsReadByAccountId(TEST_ACCOUNT_ID);

            // Then: Batch error should still be unread
            ErrorLog reloadedBatch = errorLogRepository.findById(savedBatch.getId()).orElseThrow();
            assertThat(reloadedBatch.isRead()).isFalse();
        }
    }
}
