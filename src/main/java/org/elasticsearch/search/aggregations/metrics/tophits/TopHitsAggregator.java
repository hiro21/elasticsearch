/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.metrics.tophits;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.*;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.lucene.ScorerAware;
import org.elasticsearch.common.util.LongObjectPagedHashMap;
import org.elasticsearch.search.aggregations.*;
import org.elasticsearch.search.aggregations.metrics.MetricsAggregator;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.fetch.FetchPhase;
import org.elasticsearch.search.fetch.FetchSearchResult;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.InternalSearchHits;
import org.elasticsearch.search.internal.SubSearchContext;

import java.io.IOException;
import java.util.Map;

/**
 */
public class TopHitsAggregator extends MetricsAggregator implements ScorerAware {

    /** Simple wrapper around a top-level collector and the current leaf collector. */
    private static class TopDocsAndLeafCollector {
        final TopDocsCollector<?> topLevelCollector;
        LeafCollector leafCollector;

        TopDocsAndLeafCollector(TopDocsCollector<?> topLevelCollector) {
            this.topLevelCollector = topLevelCollector;
        }
    }

    private final FetchPhase fetchPhase;
    private final SubSearchContext subSearchContext;
    private final LongObjectPagedHashMap<TopDocsAndLeafCollector> topDocsCollectors;

    private Scorer currentScorer;
    private LeafReaderContext currentContext;

    public TopHitsAggregator(FetchPhase fetchPhase, SubSearchContext subSearchContext, String name, AggregationContext context, Aggregator parent, Map<String, Object> metaData) throws IOException {
        super(name, context, parent, metaData);
        this.fetchPhase = fetchPhase;
        topDocsCollectors = new LongObjectPagedHashMap<>(1, context.bigArrays());
        this.subSearchContext = subSearchContext;
        context.registerScorerAware(this);
    }

    @Override
    public boolean shouldCollect() {
        return true;
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrdinal) {
        TopDocsAndLeafCollector topDocsCollector = topDocsCollectors.get(owningBucketOrdinal);
        if (topDocsCollector == null) {
            return buildEmptyAggregation();
        } else {
            TopDocs topDocs = topDocsCollector.topLevelCollector.topDocs();
            if (topDocs.totalHits == 0) {
                return buildEmptyAggregation();
            }

            subSearchContext.queryResult().topDocs(topDocs);
            int[] docIdsToLoad = new int[topDocs.scoreDocs.length];
            for (int i = 0; i < topDocs.scoreDocs.length; i++) {
                docIdsToLoad[i] = topDocs.scoreDocs[i].doc;
            }
            subSearchContext.docIdsToLoad(docIdsToLoad, 0, docIdsToLoad.length);
            fetchPhase.execute(subSearchContext);
            FetchSearchResult fetchResult = subSearchContext.fetchResult();
            InternalSearchHit[] internalHits = fetchResult.fetchResult().hits().internalHits();
            for (int i = 0; i < internalHits.length; i++) {
                ScoreDoc scoreDoc = topDocs.scoreDocs[i];
                InternalSearchHit searchHitFields = internalHits[i];
                searchHitFields.shard(subSearchContext.shardTarget());
                searchHitFields.score(scoreDoc.score);
                if (scoreDoc instanceof FieldDoc) {
                    FieldDoc fieldDoc = (FieldDoc) scoreDoc;
                    searchHitFields.sortValues(fieldDoc.fields);
                }
            }
            return new InternalTopHits(name, subSearchContext.from(), subSearchContext.size(), topDocs, fetchResult.hits());
        }
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return new InternalTopHits(name, subSearchContext.from(), subSearchContext.size(), Lucene.EMPTY_TOP_DOCS, InternalSearchHits.empty());
    }

    @Override
    public void collect(int docId, long bucketOrdinal) throws IOException {
        TopDocsAndLeafCollector collectors = topDocsCollectors.get(bucketOrdinal);
        if (collectors == null) {
            Sort sort = subSearchContext.sort();
            int topN = subSearchContext.from() + subSearchContext.size();
            TopDocsCollector<?> topLevelCollector = sort != null ? TopFieldCollector.create(sort, topN, true, subSearchContext.trackScores(), subSearchContext.trackScores()) : TopScoreDocCollector.create(topN);
            collectors = new TopDocsAndLeafCollector(topLevelCollector);
            collectors.leafCollector = collectors.topLevelCollector.getLeafCollector(currentContext);
            collectors.leafCollector.setScorer(currentScorer);
            topDocsCollectors.put(bucketOrdinal, collectors);
        }
        collectors.leafCollector.collect(docId);
    }

    @Override
    public void setNextReader(LeafReaderContext context) {
        this.currentContext = context;
        for (LongObjectPagedHashMap.Cursor<TopDocsAndLeafCollector> cursor : topDocsCollectors) {
            try {
                cursor.value.leafCollector = cursor.value.topLevelCollector.getLeafCollector(currentContext);
            } catch (IOException e) {
                throw ExceptionsHelper.convertToElastic(e);
            }
        }
    }

    @Override
    public void setScorer(Scorer scorer) {
        this.currentScorer = scorer;
        for (LongObjectPagedHashMap.Cursor<TopDocsAndLeafCollector> cursor : topDocsCollectors) {
            try {
                cursor.value.leafCollector.setScorer(scorer);
            } catch (IOException e) {
                throw ExceptionsHelper.convertToElastic(e);
            }
        }
    }

    @Override
    protected void doClose() {
        Releasables.close(topDocsCollectors);
    }

    public static class Factory extends AggregatorFactory {

        private final FetchPhase fetchPhase;
        private final SubSearchContext subSearchContext;

        public Factory(String name, FetchPhase fetchPhase, SubSearchContext subSearchContext) {
            super(name, InternalTopHits.TYPE.name());
            this.fetchPhase = fetchPhase;
            this.subSearchContext = subSearchContext;
        }

        @Override
        public Aggregator createInternal(AggregationContext aggregationContext, Aggregator parent, boolean collectsFromSingleBucket, Map<String, Object> metaData) throws IOException {
            return new TopHitsAggregator(fetchPhase, subSearchContext, name, aggregationContext, parent, metaData);
        }

        @Override
        public AggregatorFactory subFactories(AggregatorFactories subFactories) {
            throw new AggregationInitializationException("Aggregator [" + name + "] of type [" + type + "] cannot accept sub-aggregations");
        }

    }
}
