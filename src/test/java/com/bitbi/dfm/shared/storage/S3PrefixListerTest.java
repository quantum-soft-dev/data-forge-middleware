package com.bitbi.dfm.shared.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Incremental prefix listing (issue #122): a mid-pagination failure must return the pages already
 * read, with lastModified per object and a truncation flag, instead of throwing the whole walk away.
 *
 * <p>Since issue #199 the walk has two forms over one implementation: {@link S3PrefixLister#listAll}
 * materializes the prefix for a caller that needs all of it at once (the wipe compares keys against
 * an instant, {@code requireCompleteKeys} needs the whole set or nothing), and
 * {@link S3PrefixLister#forEachPage} hands a caller one page at a time so a filter never holds more
 * than a page. Truncation means the same thing in both: what was read is kept and the flag says the
 * walk stopped early.</p>
 */
@DisplayName("S3PrefixLister")
class S3PrefixListerTest {

    private static final Instant T1 = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-01T11:00:00Z");
    private static final Instant T3 = Instant.parse("2026-08-01T12:00:00Z");

    @Test
    @DisplayName("a complete walk returns every object, its lastModified, and truncated=false")
    void completeWalkReturnsEveryObject() {
        S3PrefixListing listing = S3PrefixLister.collect(List.of(
                page(object("a", T1), object("b", T2)),
                page(object("c", T3))));

        assertThat(listing.truncated()).isFalse();
        assertThat(listing.objects()).containsExactly(
                new S3ListedObject("a", T1),
                new S3ListedObject("b", T2),
                new S3ListedObject("c", T3));
        assertThat(listing.keys()).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("a throw while fetching a later page keeps the pages already read")
    void midPaginationFailureKeepsPagesAlreadyRead() {
        S3PrefixListing listing = S3PrefixLister.collect(throwingAfter(
                List.of(page(object("a", T1), object("b", T2))),
                S3Exception.builder().statusCode(503).message("SlowDown").build()));

        assertThat(listing.truncated()).isTrue();
        assertThat(listing.objects()).containsExactly(
                new S3ListedObject("a", T1),
                new S3ListedObject("b", T2));
    }

    @Test
    @DisplayName("a throw on the first page is an empty truncated listing, not an exception")
    void firstPageFailureIsEmptyTruncated() {
        S3PrefixListing listing = S3PrefixLister.collect(throwingAfter(
                List.of(),
                SdkClientException.create("connection reset")));

        assertThat(listing.truncated()).isTrue();
        assertThat(listing.objects()).isEmpty();
    }

    @Test
    @DisplayName("both storage callers share the same walk: listAll never throws on an S3 outage")
    void listAllReturnsTruncatedInsteadOfThrowing() {
        S3Client s3 = mock(S3Client.class);
        ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
        when(s3.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);
        when(paginator.iterator()).thenReturn(throwingAfter(
                List.of(page(object("kept", T1))),
                S3Exception.builder().statusCode(500).message("InternalError").build()).iterator());

        S3PrefixListing listing = S3PrefixLister.listAll(s3, "bucket", "checkpoints/site/");

        assertThat(listing.truncated()).isTrue();
        assertThat(listing.keys()).containsExactly("kept");
    }

    @Test
    @DisplayName("requireCompleteKeys throws when the walk was truncated")
    void requireCompleteKeysThrowsWhenTruncated() {
        S3Client s3 = mock(S3Client.class);
        ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
        when(s3.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);
        when(paginator.iterator()).thenReturn(throwingAfter(
                List.of(page(object("kept", T1))),
                S3Exception.builder().statusCode(500).message("InternalError").build()).iterator());

        assertThatThrownBy(() -> S3PrefixLister.requireCompleteKeys(s3, "bucket", "egress/site/"))
                .isInstanceOf(S3PrefixLister.IncompletePrefixException.class);
    }

    @Test
    @DisplayName("requireCompleteKeys returns every key of a finished walk")
    void requireCompleteKeysReturnsKeysWhenComplete() {
        S3Client s3 = mock(S3Client.class);
        ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
        when(s3.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);
        when(paginator.iterator()).thenReturn(List.of(page(object("a", T1), object("b", T2))).iterator());

        assertThat(S3PrefixLister.requireCompleteKeys(s3, "bucket", "egress/site/"))
                .containsExactly("a", "b");
    }

    // ------------------------------------------------------------------ page by page (#199)

    @Test
    @DisplayName("forEachPage hands over one page at a time and never a joined listing")
    void forEachPageDeliversPagesSeparately() {
        List<List<S3ListedObject>> pages = new ArrayList<>();

        S3PrefixLister.S3PrefixWalk walk = S3PrefixLister.forEachPage(List.of(
                page(object("a", T1), object("b", T2)),
                page(object("c", T3))), pages::add);

        assertThat(walk.truncated()).isFalse();
        assertThat(walk.objectsRead()).isEqualTo(3);
        assertThat(pages).containsExactly(
                List.of(new S3ListedObject("a", T1), new S3ListedObject("b", T2)),
                List.of(new S3ListedObject("c", T3)));
    }

    @Test
    @DisplayName("a throw while fetching a later page keeps the pages already handed over")
    void forEachPageKeepsPagesAlreadyHandedOver() {
        List<List<S3ListedObject>> pages = new ArrayList<>();

        S3PrefixLister.S3PrefixWalk walk = S3PrefixLister.forEachPage(throwingAfter(
                List.of(page(object("a", T1), object("b", T2))),
                S3Exception.builder().statusCode(503).message("SlowDown").build()), pages::add);

        assertThat(walk.truncated()).isTrue();
        assertThat(walk.objectsRead()).isEqualTo(2);
        assertThat(pages).containsExactly(
                List.of(new S3ListedObject("a", T1), new S3ListedObject("b", T2)));
    }

    @Test
    @DisplayName("a throw on the first page hands over nothing and says the walk stopped early")
    void forEachPageFirstPageFailureHandsOverNothing() {
        List<List<S3ListedObject>> pages = new ArrayList<>();

        S3PrefixLister.S3PrefixWalk walk = S3PrefixLister.forEachPage(throwingAfter(
                List.of(), SdkClientException.create("connection reset")), pages::add);

        assertThat(walk.truncated()).isTrue();
        assertThat(walk.objectsRead()).isZero();
        assertThat(pages).isEmpty();
    }

    @Test
    @DisplayName("a consumer that throws is the caller's failure, not a truncated listing")
    void forEachPageDoesNotSwallowAConsumerFailure() {
        // The consumer of this walk deletes objects, so it can raise the very exception types the
        // walk classifies as truncation. Reporting that as "the listing stopped early" would hide a
        // caller's bug behind a flag that means the opposite, and would tell a sweep it had read
        // fewer objects than it had.
        assertThatThrownBy(() -> S3PrefixLister.forEachPage(
                List.of(page(object("a", T1))),
                objects -> {
                    throw S3Exception.builder().statusCode(403).message("AccessDenied").build();
                }))
                .isInstanceOf(S3Exception.class);
    }

    @Test
    @DisplayName("a paginator that fails when the walk opens it is an empty truncated walk")
    void forEachPageSurvivesAFailureOpeningTheWalk() {
        List<List<S3ListedObject>> pages = new ArrayList<>();

        S3PrefixLister.S3PrefixWalk walk = S3PrefixLister.forEachPage(
                () -> {
                    throw SdkClientException.create("failed to open the walk");
                }, pages::add);

        assertThat(walk.truncated()).isTrue();
        assertThat(walk.objectsRead()).isZero();
        assertThat(pages).isEmpty();
    }

    @Test
    @DisplayName("forEachPage over a client never throws: a failed paginator is an empty truncated walk")
    void forEachPageOverAClientReportsTruncationInsteadOfThrowing() {
        S3Client s3 = mock(S3Client.class);
        when(s3.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
                .thenThrow(SdkClientException.create("no credentials"));
        List<List<S3ListedObject>> pages = new ArrayList<>();

        S3PrefixLister.S3PrefixWalk walk =
                S3PrefixLister.forEachPage(s3, "bucket", "delta/site/segments/", pages::add);

        assertThat(walk.truncated()).isTrue();
        assertThat(walk.objectsRead()).isZero();
        assertThat(pages).isEmpty();
    }

    @Test
    @DisplayName("forEachPage over a client walks the prefix page by page")
    void forEachPageOverAClientWalksThePrefix() {
        S3Client s3 = mock(S3Client.class);
        ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
        when(s3.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);
        when(paginator.iterator())
                .thenReturn(List.of(page(object("a", T1)), page(object("b", T2))).iterator());
        List<List<S3ListedObject>> pages = new ArrayList<>();

        S3PrefixLister.S3PrefixWalk walk =
                S3PrefixLister.forEachPage(s3, "bucket", "delta/site/segments/", pages::add);

        assertThat(walk.truncated()).isFalse();
        assertThat(walk.objectsRead()).isEqualTo(2);
        assertThat(pages).containsExactly(
                List.of(new S3ListedObject("a", T1)), List.of(new S3ListedObject("b", T2)));
    }

    private static ListObjectsV2Response page(S3Object... objects) {
        return ListObjectsV2Response.builder().contents(objects).build();
    }

    private static S3Object object(String key, Instant lastModified) {
        return S3Object.builder().key(key).lastModified(lastModified).build();
    }

    /**
     * Yields {@code pages} and then throws {@code failure} on the next {@code next()} — the shape
     * of a paginator that dies while fetching page N+1.
     */
    private static Iterable<ListObjectsV2Response> throwingAfter(
            List<ListObjectsV2Response> pages, RuntimeException failure) {
        return () -> new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index <= pages.size();
            }

            @Override
            public ListObjectsV2Response next() {
                if (index < pages.size()) {
                    return pages.get(index++);
                }
                if (index == pages.size()) {
                    index++;
                    throw failure;
                }
                throw new NoSuchElementException();
            }
        };
    }
}
