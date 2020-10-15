// Copyright 2020 Oath Inc.
// Licensed under the terms of the Apache license. Please see LICENSE.md file distributed with this work for terms.
package com.yahoo.bard.webservice.web.util;

import static com.yahoo.bard.webservice.web.handlers.workflow.DruidWorkflow.REQUEST_WORKFLOW_TIMER;
import static com.yahoo.bard.webservice.web.handlers.workflow.DruidWorkflow.RESPONSE_WORKFLOW_TIMER;
import static com.yahoo.bard.webservice.config.BardFeatureFlag.CACHE_PARTIAL_DATA;
import static com.yahoo.bard.webservice.web.handlers.PartialDataRequestHandler.getPartialIntervalsWithDefault;
import static com.yahoo.bard.webservice.web.handlers.VolatileDataRequestHandler.getVolatileIntervalsWithDefault;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.yahoo.bard.webservice.config.SystemConfig;
import com.yahoo.bard.webservice.config.SystemConfigProvider;
import com.yahoo.bard.webservice.util.SimplifiedIntervalList;
import com.yahoo.bard.webservice.application.MetricRegistryFactory;
import com.yahoo.bard.webservice.data.cache.TupleDataCache;
import com.yahoo.bard.webservice.druid.model.query.DruidAggregationQuery;
import com.yahoo.bard.webservice.logging.RequestLog;
import com.yahoo.bard.webservice.logging.blocks.BardQueryInfo;
import com.yahoo.bard.webservice.metadata.QuerySigningService;
import com.yahoo.bard.webservice.util.Utils;
import com.yahoo.bard.webservice.web.handlers.RequestContext;
import com.yahoo.bard.webservice.web.responseprocessors.ResponseProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;


/**
 * A utility class to assist with caching.
 */
public class CacheService {

    private static final Logger LOG = LoggerFactory.getLogger(CacheService.class);
    private static final SystemConfig SYSTEM_CONFIG = SystemConfigProvider.getInstance();
    private final long maxDruidResponseLengthToCache = SYSTEM_CONFIG.getLongProperty(
            SYSTEM_CONFIG.getPackageVariableName(
                    "druid_max_response_length_to_cache"
            ),
            Long.MAX_VALUE
    );
    private static final MetricRegistry REGISTRY = MetricRegistryFactory.getRegistry();
    public static final Meter CACHE_HITS = REGISTRY.meter("queries.meter.cache.hits");
    public static final Meter CACHE_POTENTIAL_HITS = REGISTRY.meter("queries.meter.cache.potential_hits");
    public static final Meter CACHE_MISSES = REGISTRY.meter("queries.meter.cache.misses");
    public static final Meter CACHE_REQUESTS = REGISTRY.meter("queries.meter.cache.total");

    TupleDataCache<String, Long, String> dataCache;
    QuerySigningService<Long> querySigningService;
    ObjectMapper objectMapper;
    ObjectWriter writer;
    /**
     * Constructor.
     *
     * @param dataCache  The cache instance
     * @param querySigningService  The service to generate query signatures
     * @param objectMapper A JSON object mapper, used to parse JSON response
     */
    public CacheService(
            final TupleDataCache<String, Long, String> dataCache,
            final QuerySigningService<Long> querySigningService,
            ObjectMapper objectMapper
    ) {
        this.dataCache = dataCache;
        this.querySigningService = querySigningService;
        this.objectMapper = objectMapper;
        this.writer = objectMapper.writer();
    }


    /**
     * Read cache.
     *
     * @param context The context data from the request processing chain
     * @param druidQuery The query being processed
     *
     * @return Response
     */
    public String readCache(
            RequestContext context,
            DruidAggregationQuery<?> druidQuery
    ) throws JsonProcessingException {
        final TupleDataCache.DataEntry<String, Long, String> cacheEntry = dataCache.get(getKey(druidQuery));
        CACHE_REQUESTS.mark(1);

        if (cacheEntry != null) {
            if (
                    querySigningService.getSegmentSetId(druidQuery)
                            .map(id -> Objects.equals(cacheEntry.getMeta(), id))
                            .orElse(false)
            ) {
                try {
                    if (context.getNumberOfOutgoing().decrementAndGet() == 0) {
                        RequestLog.stopTiming(REQUEST_WORKFLOW_TIMER);
                    }

                    if (context.getNumberOfIncoming().decrementAndGet() == 0) {
                        RequestLog.startTiming(RESPONSE_WORKFLOW_TIMER);
                    }
                    CACHE_HITS.mark(1);
                    BardQueryInfo.getBardQueryInfo().incrementCountCacheHits();
                    return cacheEntry.getValue();

                } catch (Exception e) {
                    LOG.warn("Error processing cached value: ", e);
                }
            } else {
                LOG.debug("Cache entry present but invalid for query with id: {}", RequestLog.getId());
                CACHE_POTENTIAL_HITS.mark(1);
                CACHE_MISSES.mark(1);
            }
        } else {
            CACHE_MISSES.mark(1);
        }
        return null;
    }

    /**
     * Write cache.
     *
     * @param response The response handler
     * @param json Json value to be written to cache as string
     * @param druidQuery The query being processed
     */
    public void writeCache(
            ResponseProcessor response,
            JsonNode json,
            DruidAggregationQuery<?> druidQuery
            ) throws JsonProcessingException {
        if (CACHE_PARTIAL_DATA.isOn() || isCacheable(response)) {
            String cacheKey = getKey(druidQuery);
            String valueString = null;
            try {
                valueString = writer.writeValueAsString(json);
                int valueLength = valueString.length();
                if (valueLength <= maxDruidResponseLengthToCache) {
                    dataCache.set(
                            cacheKey,
                            Long.parseLong(
                                    String.valueOf(
                                            querySigningService.getSegmentSetId(druidQuery).orElse(null)
                                    )
                            ),
                            valueString
                    );
                } else {
                    LOG.debug(
                            "Response not cached. Length of {} exceeds max value length of {}",
                            valueLength,
                            maxDruidResponseLengthToCache
                    );
                }
            } catch (Exception e) {
                LOG.warn(
                        "Unable to cache {}value of size: {}",
                        valueString == null ? "null " : "",
                        valueString == null ? "N/A" : valueString.length(),
                        e
                );
            }
        }
    }


    /**
     * A request is cacheable if it does not refer to partial data.
     *
     * @param response The response handler
     *
     * @return whether request can be cached
     */
    public boolean isCacheable(ResponseProcessor response) {
        SimplifiedIntervalList missingIntervals = getPartialIntervalsWithDefault(response.getResponseContext());
        SimplifiedIntervalList volatileIntervals = getVolatileIntervalsWithDefault(response.getResponseContext());

        return missingIntervals.isEmpty() && volatileIntervals.isEmpty();
    }

    /**
     * Construct the cache key.
     * Current implementation includes all the fields of the druidQuery besides the context.
     *
     * @param druidQuery  The druid query.
     *
     * @return The cache key as a String.
     * @throws JsonProcessingException if the druid query cannot be serialized to JSON
     */
    protected String getKey(DruidAggregationQuery<?> druidQuery)
            throws JsonProcessingException {
        JsonNode root = objectMapper.valueToTree(druidQuery);
        Utils.canonicalize(root, objectMapper, false);
        return writer.writeValueAsString(root);
    }
}
