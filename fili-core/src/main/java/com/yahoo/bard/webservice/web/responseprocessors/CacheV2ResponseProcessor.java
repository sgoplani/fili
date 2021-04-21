// Copyright 2016 Yahoo Inc.
// Licensed under the terms of the Apache license. Please see LICENSE.md file distributed with this work for terms.
package com.yahoo.bard.webservice.web.responseprocessors;

import static com.yahoo.bard.webservice.config.BardFeatureFlag.CACHE_PARTIAL_DATA;
import static com.yahoo.bard.webservice.web.handlers.PartialDataRequestHandler.getPartialIntervalsWithDefault;
import static com.yahoo.bard.webservice.web.handlers.VolatileDataRequestHandler.getVolatileIntervalsWithDefault;

import com.yahoo.bard.webservice.application.MetricRegistryFactory;
import com.yahoo.bard.webservice.config.SystemConfig;
import com.yahoo.bard.webservice.config.SystemConfigProvider;
import com.yahoo.bard.webservice.data.cache.TupleDataCache;
import com.yahoo.bard.webservice.druid.client.FailureCallback;
import com.yahoo.bard.webservice.druid.client.HttpErrorCallback;
import com.yahoo.bard.webservice.druid.model.query.DruidAggregationQuery;
import com.yahoo.bard.webservice.logging.blocks.BardCacheInfo;
import com.yahoo.bard.webservice.logging.blocks.BardQueryInfo;
import com.yahoo.bard.webservice.metadata.QuerySigningService;
import com.yahoo.bard.webservice.util.SimplifiedIntervalList;
import com.yahoo.bard.webservice.web.util.QuerySignedCacheService;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import javax.validation.constraints.NotNull;
import javax.xml.bind.DatatypeConverter;

/**
 * A response processor which caches the results if appropriate after completing a query.
 */
public class CacheV2ResponseProcessor implements ResponseProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(CacheV2ResponseProcessor.class);
    private static final SystemConfig SYSTEM_CONFIG = SystemConfigProvider.getInstance();
    private static final MetricRegistry REGISTRY = MetricRegistryFactory.getRegistry();
    public static final Meter CACHE_SET_FAILURES = REGISTRY.meter("queries.meter.cache.put.failures");

    private final long maxDruidResponseLengthToCache = SYSTEM_CONFIG.getLongProperty(
            SYSTEM_CONFIG.getPackageVariableName(
                    "druid_max_response_length_to_cache"
            ),
            Long.MAX_VALUE
    );

    private final ResponseProcessor next;
    private final String cacheKey;
    private final @NotNull TupleDataCache<String, Long, String> dataCache;
    private final @NotNull QuerySigningService<Long> querySigningService;

    protected final ObjectWriter writer;

    /**
     * Constructor.
     *
     * @param next  Next ResponseProcessor in the chain
     * @param cacheKey  Key into which to write a cache entry
     * @param dataCache  The cache into which to write a cache entry
     * @param querySigningService  Service to use for signing the queries in the cache key with their metadata
     * @param mapper  An object mapper to use for processing Json
     */
    public CacheV2ResponseProcessor(
            ResponseProcessor next,
            String cacheKey,
            TupleDataCache<String, Long, String> dataCache,
            QuerySigningService<Long> querySigningService,
            ObjectMapper mapper
    ) {
        this.next = next;
        this.cacheKey = cacheKey;
        this.dataCache = dataCache;
        this.querySigningService = querySigningService;
        this.writer = mapper.writer();
    }

    @Override
    public ResponseContext getResponseContext() {
        return next.getResponseContext();
    }

    @Override
    public FailureCallback getFailureCallback(DruidAggregationQuery<?> druidQuery) {
        return next.getFailureCallback(druidQuery);
    }

    @Override
    public HttpErrorCallback getErrorCallback(DruidAggregationQuery<?> druidQuery) {
        return next.getErrorCallback(druidQuery);
    }

    @Override
    public void processResponse(JsonNode json, DruidAggregationQuery<?> druidQuery, LoggingContext metadata) {
        String querySignatureHash = String.valueOf(querySigningService.getSegmentSetId(druidQuery).orElse(null));
        next.processResponse(json, druidQuery, metadata);
        if (CACHE_PARTIAL_DATA.isOn() || isCacheable()) {
            String valueString = null;
            try {
                valueString = writer.writeValueAsString(json);
                int valueLength = valueString.length();
                if (valueLength <= maxDruidResponseLengthToCache) {
                    dataCache.set(
                            cacheKey,
                            querySigningService.getSegmentSetId(druidQuery).orElse(null),
                            valueString
                    );
                } else {
                    LOG.debug(
                            "Response not cached for query with key cksum {}." +
                                    "Length of {} exceeds max value length of {}",
                            getMD5Cksum(cacheKey),
                            valueLength,
                            maxDruidResponseLengthToCache
                    );
                }
            } catch (Exception e) {
                //mark and log the cache put failure
                CACHE_SET_FAILURES.mark(1);
                BardQueryInfo.getBardQueryInfo().incrementCountCacheSetFailures();
                BardQueryInfo.getBardQueryInfo().addCacheInfo(getMD5Cksum(cacheKey),
                        new BardCacheInfo(
                                QuerySignedCacheService.LOG_CACHE_SET_FAILURES,
                                cacheKey.length(),
                                getMD5Cksum(cacheKey),
                                querySignatureHash != null
                                        ? CacheV2ResponseProcessor.getMD5Cksum(querySignatureHash)
                                        : null,
                                valueString != null ? valueString.length() : 0
                        )
                );
                LOG.warn(
                        "Unable to cache {} value of size: {} and key cksum: {} ",
                        valueString == null ? "null " : "",
                        valueString == null ? "N/A" : valueString.length(),
                        getMD5Cksum(cacheKey),
                        e
                );
            }
        }
    }

    /**
     * A request is cacheable if it does not refer to partial data.
     *
     * @return whether request can be cached
     */
    protected boolean isCacheable() {
        SimplifiedIntervalList missingIntervals = getPartialIntervalsWithDefault(getResponseContext());
        SimplifiedIntervalList volatileIntervals = getVolatileIntervalsWithDefault(getResponseContext());

        return missingIntervals.isEmpty() && volatileIntervals.isEmpty();
    }

    /**
     * Generate the Checksum of cacheKey using MD5 algorithm.
     * @param cacheKey cache key
     *
     * @return String representation of the Cksum
     */
    public static String getMD5Cksum(String cacheKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(cacheKey.getBytes("UTF-8"));
            return bytesToHex(hash); // make it readable
        } catch (NoSuchAlgorithmException ex) {
            String msg = "Unable to initialize hash generator with default MD5 algorithm ";
            LOG.warn(msg, ex);
            throw new RuntimeException(msg, ex);
        } catch (UnsupportedEncodingException x) {
            String msg = "Unable to initialize checksum byte array ";
            LOG.warn(msg, x);
            throw new RuntimeException(msg, x);
        } catch (Exception exception) {
            String msg = "Failed to generate checksum for cache key";
            LOG.warn(msg, exception);
            throw new RuntimeException(msg, exception);
        }
    }

    /**
     * Converts bytes array to the hex String.
     * @param hash array of bytes to be converted in Hex
     *
     * @return String representation of the checksum
     */
    public static String  bytesToHex(byte[] hash) {
        return DatatypeConverter.printHexBinary(hash)
                .toLowerCase(Locale.ENGLISH);
    }
}
