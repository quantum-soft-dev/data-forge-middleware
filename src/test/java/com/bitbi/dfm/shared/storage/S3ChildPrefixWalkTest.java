package com.bitbi.dfm.shared.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The delimiter half of the shared walk (issue #158): which <em>sites</em> have objects under a
 * root prefix, asked of the bucket rather than of the database.
 *
 * <p>It has to be the bucket, because the database is not a complete list of the sites whose
 * objects are still there: {@code SiteService.deleteSite} hard-deletes the site row and never
 * touches {@code delta/} or {@code checkpoints/}, so every object of a deleted site outlives every
 * row that could have named it.</p>
 */
@DisplayName("S3PrefixLister child-prefix walk (#158)")
class S3ChildPrefixWalkTest {

    @Test
    @DisplayName("a complete walk returns every child prefix across pages")
    void completeWalkReturnsEveryChildPrefix() {
        S3ChildPrefixListing listing = S3PrefixLister.collectChildPrefixes(List.of(
                page("checkpoints/a/", "checkpoints/b/"),
                page("checkpoints/c/")));

        assertThat(listing.truncated()).isFalse();
        assertThat(listing.prefixes())
                .containsExactly("checkpoints/a/", "checkpoints/b/", "checkpoints/c/");
    }

    @Test
    @DisplayName("a throw while fetching a later page keeps the prefixes already read")
    void midPaginationFailureKeepsPrefixesAlreadyRead() {
        S3ChildPrefixListing listing = S3PrefixLister.collectChildPrefixes(throwingAfter(
                List.of(page("checkpoints/a/")),
                S3Exception.builder().statusCode(503).message("SlowDown").build()));

        assertThat(listing.truncated()).isTrue();
        assertThat(listing.prefixes()).containsExactly("checkpoints/a/");
    }

    @Test
    @DisplayName("a throw on the first page is an empty truncated listing, not an exception")
    void firstPageFailureIsEmptyTruncated() {
        S3ChildPrefixListing listing = S3PrefixLister.collectChildPrefixes(throwingAfter(
                List.of(),
                SdkClientException.create("connection reset")));

        assertThat(listing.truncated()).isTrue();
        assertThat(listing.prefixes()).isEmpty();
    }

    @Test
    @DisplayName("the request carries the delimiter, so a page answers one level and not the bucket")
    void requestAsksForOneLevelOnly() {
        S3Client s3 = mock(S3Client.class);
        ListObjectsV2Iterable paginator = mock(ListObjectsV2Iterable.class);
        when(s3.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(paginator);
        when(paginator.iterator()).thenReturn(List.of(page("delta/a/")).iterator());

        S3ChildPrefixListing listing = S3PrefixLister.listChildPrefixes(s3, "bucket", "delta/");

        var request = forClass(ListObjectsV2Request.class);
        verify(s3).listObjectsV2Paginator(request.capture());
        assertThat(request.getValue().prefix()).isEqualTo("delta/");
        assertThat(request.getValue().delimiter()).isEqualTo("/");
        assertThat(listing.prefixes()).containsExactly("delta/a/");
    }

    @Test
    @DisplayName("a paginator that cannot even be opened is truncated rather than a throw")
    void listChildPrefixesNeverThrows() {
        S3Client s3 = mock(S3Client.class);
        when(s3.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("AccessDenied").build());

        S3ChildPrefixListing listing = S3PrefixLister.listChildPrefixes(s3, "bucket", "delta/");

        assertThat(listing.truncated()).isTrue();
        assertThat(listing.prefixes()).isEmpty();
    }

    private static ListObjectsV2Response page(String... prefixes) {
        List<CommonPrefix> common = new ArrayList<>();
        for (String prefix : prefixes) {
            common.add(CommonPrefix.builder().prefix(prefix).build());
        }
        return ListObjectsV2Response.builder().commonPrefixes(common).build();
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
