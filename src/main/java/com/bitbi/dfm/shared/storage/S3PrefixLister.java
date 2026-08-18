package com.bitbi.dfm.shared.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared incremental {@code ListObjectsV2} walk (issue #122).
 *
 * <p>Walks pages one by one. On {@code S3Exception} or {@code SdkClientException} returns the
 * objects already read with {@code truncated=true} instead of throwing the walk away.</p>
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
        try {
            return collect(client.listObjectsV2Paginator(
                    ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build()));
        } catch (S3Exception | SdkClientException e) {
            // Construction of the paginator itself failed — nothing was read.
            log.warn("Could not start listing objects under {}", prefix, e);
            return S3PrefixListing.truncated(List.of());
        }
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
        try {
            for (ListObjectsV2Response page : pages) {
                for (S3Object object : page.contents()) {
                    objects.add(new S3ListedObject(object.key(), object.lastModified()));
                }
            }
            return S3PrefixListing.complete(objects);
        } catch (S3Exception | SdkClientException e) {
            log.warn("Prefix listing stopped after {} object(s); returning a truncated result",
                    objects.size(), e);
            return S3PrefixListing.truncated(objects);
        }
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
