package com.bitbi.dfm.shared.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
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
}
