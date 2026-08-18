package com.bitbi.dfm.shared.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared incremental {@code ListObjectsV2} walk (issue #122).
 *
 * <p>Walks pages one by one. On {@code S3Exception} or {@code SdkClientException} returns the
 * objects already read with {@code truncated=true} instead of throwing the walk away.</p>
 *
 * <p>Two forms over one walk (issue #199). {@link #listAll} materializes the prefix, which is what
 * a caller needs when it must see the whole set before it can act on any of it — the wipe compares
 * every key against one instant, {@link #requireCompleteKeys} needs all of them or none.
 * {@link #forEachPage} hands the caller one page at a time, so a caller that only <em>filters</em>
 * a prefix holds a page rather than a prefix; that is what bounds the orphan sweep, whose first
 * deleting pass is by construction the largest listing this application ever takes.</p>
 */
public final class S3PrefixLister {

    private static final Logger log = LoggerFactory.getLogger(S3PrefixLister.class);

    private S3PrefixLister() {
    }

    /**
     * Every object under {@code prefix}, following continuation tokens.
     *
     * @param client the S3 client
     * @param bucket bucket name
     * @param prefix key prefix
     * @return the pages read; {@link S3PrefixListing#truncated()} when the walk stopped early
     */
    public static S3PrefixListing listAll(S3Client client, String bucket, String prefix) {
        List<S3ListedObject> objects = new ArrayList<>();
        S3PrefixWalk walk = forEachPage(client, bucket, prefix, objects::addAll);
        return walk.truncated() ? S3PrefixListing.truncated(objects) : S3PrefixListing.complete(objects);
    }

    /**
     * The same walk, handed to {@code page} one page at a time (issue #199).
     *
     * <p>For a caller that only needs to <em>filter</em> a prefix: nothing here accumulates, so the
     * peak is one page rather than one prefix. Truncation means what it means everywhere else —
     * the pages already handed over stand, and {@link S3PrefixWalk#truncated()} says the walk
     * stopped early, which a caller that deletes must read as "fewer objects this pass", never as
     * "these are all of them".</p>
     *
     * <p>A failure raised by {@code page} itself is <b>not</b> caught: it is the caller's, and
     * reporting it as a truncated listing would hide it behind a flag that means the opposite.</p>
     *
     * @param client the S3 client
     * @param bucket bucket name
     * @param prefix key prefix
     * @param page   receives each page, in the order S3 returns them; never {@code null}, possibly
     *               empty
     * @return how many objects were handed over, and whether the walk stopped early
     */
    public static S3PrefixWalk forEachPage(S3Client client, String bucket, String prefix,
                                           Consumer<List<S3ListedObject>> page) {
        Iterable<ListObjectsV2Response> pages;
        try {
            pages = client.listObjectsV2Paginator(
                    ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build());
        } catch (S3Exception | SdkClientException e) {
            // Construction of the paginator itself failed — nothing was read.
            log.warn("Could not start listing objects under {}", prefix, e);
            return new S3PrefixWalk(0L, true);
        }
        return forEachPage(pages, page);
    }

    /**
     * Every key under {@code prefix}, or a throw if the walk was incomplete.
     *
     * <p>Callers that need a complete key set (batch deletion, retention) use this. The wipe
     * uses {@link #listAll} and inspects {@link S3PrefixListing#truncated()} itself.</p>
     *
     * @param client the S3 client
     * @param bucket bucket name
     * @param prefix key prefix
     * @return every key under the prefix
     * @throws IncompletePrefixException when the walk stopped early
     */
    public static List<String> requireCompleteKeys(S3Client client, String bucket, String prefix) {
        S3PrefixListing listing = listAll(client, bucket, prefix);
        if (listing.truncated()) {
            throw new IncompletePrefixException(prefix);
        }
        return listing.keys();
    }

    /**
     * The prefixes directly below {@code prefix} — one {@code ListObjectsV2} walk with a delimiter,
     * so a page answers a level rather than a whole subtree (issue #158).
     *
     * <p>This is how the orphan sweep learns which sites still have objects. It cannot ask the
     * database instead: {@code SiteService.deleteSite} hard-deletes the site row and never touches
     * {@code delta/} or {@code checkpoints/}, so a deleted site's objects outlive every row that
     * could have named them — exactly the population that most needs reclaiming.</p>
     *
     * <p>Like {@link #listAll}, it never throws: a failure returns what was read with
     * {@code truncated=true}, and a caller that deletes must read that as "fewer sites this pass",
     * which costs one interval and never a wrong deletion.</p>
     *
     * @param client the S3 client
     * @param bucket bucket name
     * @param prefix the parent prefix, ending in {@code /}
     * @return the child prefixes read; {@link S3ChildPrefixListing#truncated()} when the walk
     *         stopped early
     */
    public static S3ChildPrefixListing listChildPrefixes(S3Client client, String bucket, String prefix) {
        try {
            return collectChildPrefixes(client.listObjectsV2Paginator(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).delimiter("/").build()));
        } catch (S3Exception | SdkClientException e) {
            log.warn("Could not start listing the prefixes below {}", prefix, e);
            return S3ChildPrefixListing.truncated(List.of());
        }
    }

    /**
     * What one page-by-page walk did (issue #199).
     *
     * <p>The page-by-page twin of {@link S3PrefixListing}: the objects went to the consumer, so all
     * that is left to report is how many there were — which is what the truncation log line and the
     * sweep's own accounting need — and whether the walk finished.</p>
     *
     * @param objectsRead objects handed to the consumer before the walk ended
     * @param truncated   {@code true} when the walk stopped early
     */
    public record S3PrefixWalk(long objectsRead, boolean truncated) {
    }

    /** The paginated walk of {@code prefix} did not finish. */
    public static final class IncompletePrefixException extends RuntimeException {
        public IncompletePrefixException(String prefix) {
            super("Incomplete listing of prefix: " + prefix);
        }
    }

    /**
     * Drain an already-opened page iterable, keeping whatever arrived before a failure.
     *
     * @param pages S3 list pages
     * @return complete or truncated listing
     */
    static S3PrefixListing collect(Iterable<ListObjectsV2Response> pages) {
        List<S3ListedObject> objects = new ArrayList<>();
        S3PrefixWalk walk = forEachPage(pages, objects::addAll);
        return walk.truncated() ? S3PrefixListing.truncated(objects) : S3PrefixListing.complete(objects);
    }

    /**
     * Drain an already-opened page iterable, handing each page over as it arrives.
     *
     * <p>Only the fetch is inside the catch. The consumer runs outside it on purpose: this walk's
     * consumers talk to S3 themselves, so catching their {@code S3Exception} here would report a
     * caller's failure as a listing that stopped early.</p>
     *
     * @param pages S3 list pages
     * @param page  receives each page
     * @return how many objects were handed over, and whether the walk stopped early
     */
    static S3PrefixWalk forEachPage(Iterable<ListObjectsV2Response> pages,
                                    Consumer<List<S3ListedObject>> page) {
        long read = 0L;
        Iterator<ListObjectsV2Response> iterator;
        try {
            // Opening the walk is a fetch too: the for-each this replaced called iterator() inside
            // its own try, and a paginator is free to fail here rather than on the first next().
            iterator = pages.iterator();
        } catch (S3Exception | SdkClientException e) {
            return truncatedAt(read, e);
        }
        while (true) {
            ListObjectsV2Response response;
            try {
                if (!iterator.hasNext()) {
                    return new S3PrefixWalk(read, false);
                }
                response = iterator.next();
            } catch (S3Exception | SdkClientException e) {
                return truncatedAt(read, e);
            }
            List<S3ListedObject> objects = response.contents().stream()
                    .map(object -> new S3ListedObject(object.key(), object.lastModified()))
                    .toList();
            read += objects.size();
            page.accept(objects);
        }
    }

    /**
     * A walk that stopped early keeps what it read; the caller decides what that is worth.
     *
     * @param read how many objects had been handed over
     * @param e    the failure that ended the walk
     * @return a truncated walk
     */
    private static S3PrefixWalk truncatedAt(long read, RuntimeException e) {
        log.warn("Prefix listing stopped after {} object(s); returning a truncated result", read, e);
        return new S3PrefixWalk(read, true);
    }

    /**
     * Drain an already-opened delimiter walk, keeping whatever arrived before a failure.
     *
     * @param pages S3 list pages
     * @return complete or truncated child-prefix listing
     */
    static S3ChildPrefixListing collectChildPrefixes(Iterable<ListObjectsV2Response> pages) {
        List<String> prefixes = new ArrayList<>();
        try {
            for (ListObjectsV2Response page : pages) {
                for (CommonPrefix common : page.commonPrefixes()) {
                    prefixes.add(common.prefix());
                }
            }
            return S3ChildPrefixListing.complete(prefixes);
        } catch (S3Exception | SdkClientException e) {
            log.warn("Child-prefix listing stopped after {} prefix(es); returning a truncated result",
                    prefixes.size(), e);
            return S3ChildPrefixListing.truncated(prefixes);
        }
    }
}
