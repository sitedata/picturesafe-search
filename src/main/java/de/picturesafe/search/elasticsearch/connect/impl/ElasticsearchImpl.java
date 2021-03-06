/*
 * Copyright 2020 picturesafe media/data/bank GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.picturesafe.search.elasticsearch.connect.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.picturesafe.search.elasticsearch.config.FieldConfiguration;
import de.picturesafe.search.elasticsearch.config.IndexPresetConfiguration;
import de.picturesafe.search.elasticsearch.config.MappingConfiguration;
import de.picturesafe.search.elasticsearch.config.RestClientConfiguration;
import de.picturesafe.search.elasticsearch.connect.Elasticsearch;
import de.picturesafe.search.elasticsearch.connect.ElasticsearchAdmin;
import de.picturesafe.search.elasticsearch.connect.aggregation.resolve.FacetConverter;
import de.picturesafe.search.elasticsearch.connect.aggregation.resolve.FacetConverterChain;
import de.picturesafe.search.elasticsearch.connect.aggregation.resolve.FacetResolver;
import de.picturesafe.search.elasticsearch.connect.aggregation.search.AggregationBuilderFactory;
import de.picturesafe.search.elasticsearch.connect.aggregation.search.AggregationBuilderFactoryRegistry;
import de.picturesafe.search.elasticsearch.connect.asyncaction.RestClientBulkAction;
import de.picturesafe.search.elasticsearch.connect.asyncaction.RestClientDeleteAction;
import de.picturesafe.search.elasticsearch.connect.asyncaction.RestClientDeleteByQueryAction;
import de.picturesafe.search.elasticsearch.connect.asyncaction.RestClientIndexAction;
import de.picturesafe.search.elasticsearch.connect.asyncaction.RestClientIndexRefreshAction;
import de.picturesafe.search.elasticsearch.connect.asyncaction.RestClientSearchAction;
import de.picturesafe.search.elasticsearch.connect.asyncaction.RestClientUpdateByQueryAction;
import de.picturesafe.search.elasticsearch.connect.context.SearchContext;
import de.picturesafe.search.elasticsearch.connect.dto.FacetDto;
import de.picturesafe.search.elasticsearch.connect.dto.QueryDto;
import de.picturesafe.search.elasticsearch.connect.dto.QueryRangeDto;
import de.picturesafe.search.elasticsearch.connect.dto.SearchHitDto;
import de.picturesafe.search.elasticsearch.connect.dto.SearchResultDto;
import de.picturesafe.search.elasticsearch.connect.error.AliasAlreadyExistsException;
import de.picturesafe.search.elasticsearch.connect.error.AliasCreateException;
import de.picturesafe.search.elasticsearch.connect.error.AliasHasMoreThanOneIndexException;
import de.picturesafe.search.elasticsearch.connect.error.ElasticExceptionCause;
import de.picturesafe.search.elasticsearch.connect.error.ElasticsearchException;
import de.picturesafe.search.elasticsearch.connect.error.IndexCreateException;
import de.picturesafe.search.elasticsearch.connect.error.IndexMissingException;
import de.picturesafe.search.elasticsearch.connect.error.QuerySyntaxException;
import de.picturesafe.search.elasticsearch.connect.filter.FilterFactory;
import de.picturesafe.search.elasticsearch.connect.query.QueryFactory;
import de.picturesafe.search.elasticsearch.connect.query.QueryFactoryCaller;
import de.picturesafe.search.elasticsearch.connect.util.ElasticDateUtils;
import de.picturesafe.search.elasticsearch.connect.util.ElasticExceptionUtils;
import de.picturesafe.search.elasticsearch.connect.util.FieldConfigurationUtils;
import de.picturesafe.search.elasticsearch.connect.util.StringTrimUtility;
import de.picturesafe.search.elasticsearch.connect.util.logging.SearchRequestSourceToString;
import de.picturesafe.search.elasticsearch.connect.util.logging.SearchResponseToString;
import de.picturesafe.search.elasticsearch.model.DocumentBuilder;
import de.picturesafe.search.elasticsearch.model.ElasticsearchInfo;
import de.picturesafe.search.elasticsearch.model.IdFormat;
import de.picturesafe.search.elasticsearch.timezone.TimeZoneAware;
import de.picturesafe.search.expression.SuggestExpression;
import de.picturesafe.search.parameter.CollapseOption;
import de.picturesafe.search.parameter.InnerHitsOption;
import de.picturesafe.search.parameter.ScriptDefinition;
import de.picturesafe.search.parameter.ScriptSortOption;
import de.picturesafe.search.parameter.SearchAggregation;
import de.picturesafe.search.parameter.SortOption;
import de.picturesafe.search.util.logging.StopWatchPrettyPrint;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.lucene.search.TotalHits;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.core.MainResponse;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.InnerHitBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.AbstractBulkByScrollRequest;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.index.reindex.UpdateByQueryRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.collapse.CollapseBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.NestedSortBuilder;
import org.elasticsearch.search.sort.ScriptSortBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortMode;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.search.suggest.SuggestBuilder;
import org.elasticsearch.search.suggest.completion.CompletionSuggestionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static de.picturesafe.search.elasticsearch.connect.error.ElasticExceptionCause.Type.QUERY_SYNTAX;
import static de.picturesafe.search.elasticsearch.connect.filter.util.FilterFactoryUtils.createFilter;
import static de.picturesafe.search.elasticsearch.connect.util.ElasticDocumentUtils.getId;
import static de.picturesafe.search.elasticsearch.connect.util.ElasticRequestUtils.getRefreshPolicy;
import static de.picturesafe.search.elasticsearch.connect.util.FieldConfigurationUtils.fieldConfiguration;
import static de.picturesafe.search.elasticsearch.connect.util.FieldConfigurationUtils.isTextField;
import static de.picturesafe.search.elasticsearch.connect.util.FieldConfigurationUtils.keywordFieldName;
import static de.picturesafe.search.elasticsearch.connect.util.FieldConfigurationUtils.sortFieldName;

@Component
@SuppressWarnings({"unused"})
public class ElasticsearchImpl implements Elasticsearch, QueryFactoryCaller, TimeZoneAware {

    private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchImpl.class);
    private static final Logger QUERY_LOGGER = LoggerFactory.getLogger("elasticsearch-query");

    protected ElasticsearchAdmin elasticsearchAdmin;
    protected RestClientConfiguration restClientConfiguration;
    protected RestHighLevelClient restClient;
    protected List<QueryFactory> queryFactories;
    protected List<FilterFactory> filterFactories;
    protected String timeZone;

    protected AggregationBuilderFactoryRegistry aggregationBuilderFactoryRegistry;
    protected FacetConverterChain facetConverterChain;
    protected List<FacetResolver> facetResolvers;
    protected WriteRequestHandler writeRequestHandler;

    @Value("${elasticsearch.service.check_cluster_status_timeout:10000}")
    protected long checkClusterStatusTimeout;
    @Value("${elasticsearch.service.indexing_bulk_size:1000}")
    protected int indexingBulkSize;
    @Value("${elasticsearch.service.missing_value_sort_position:LAST}")
    protected MissingValueSortPosition missingValueSortPosition;

    protected IdFormat idFormat = IdFormat.DEFAULT;

    @Autowired
    public ElasticsearchImpl(ElasticsearchAdmin elasticsearchAdmin,
                             RestClientConfiguration restClientConfiguration,
                             List<QueryFactory> queryFactories,
                             List<FilterFactory> filterFactories,
                             @Qualifier("elasticsearchTimeZone") String timeZone) {
        this.elasticsearchAdmin = elasticsearchAdmin;
        this.restClientConfiguration = restClientConfiguration;
        this.queryFactories = queryFactories;
        this.filterFactories = filterFactories;
        this.timeZone = timeZone;
    }

    @Autowired(required = false)
    public void setAggregationBuilderFactoryRegistry(AggregationBuilderFactoryRegistry aggregationBuilderFactoryRegistry) {
        this.aggregationBuilderFactoryRegistry = aggregationBuilderFactoryRegistry;
    }

    @Autowired(required = false)
    public void setFacetConverterChain(FacetConverterChain facetConverterChain) {
        this.facetConverterChain = facetConverterChain;
    }

    @Autowired(required = false)
    public void setFacetResolvers(List<FacetResolver> facetResolvers) {
        this.facetResolvers = facetResolvers;
    }

    @Autowired(required = false)
    public void setIdFormat(IdFormat idFormat) {
        this.idFormat = idFormat;
    }

    @Autowired(required = false)
    public void setWriteRequestHandler(WriteRequestHandler writeRequestHandler) {
        this.writeRequestHandler = writeRequestHandler;
    }

    public void setCheckClusterStatusTimeout(long checkClusterStatusTimeout) {
        this.checkClusterStatusTimeout = checkClusterStatusTimeout;
    }

    public void setIndexingBulkSize(int indexingBulkSize) {
        this.indexingBulkSize = indexingBulkSize;
    }

    public void setMissingValueSortPosition(MissingValueSortPosition missingValueSortPosition) {
        this.missingValueSortPosition = missingValueSortPosition;
    }

    @PostConstruct
    public void init() {
        this.restClient = restClientConfiguration.getClient();
    }

    @Override
    public RestHighLevelClient getRestClient() {
        return restClient;
    }

    @Override
    public void addToIndex(String indexAlias, boolean applyIndexRefresh, Map<String, Object> document) {
        Validate.notEmpty(indexAlias, "Parameter 'indexAlias' may not be null or empty!");
        Validate.notNull(document, "Parameter 'document' may not be null!");

        try {
            final IndexRequest indexRequest = createIndexRequest(document, indexAlias, applyIndexRefresh);
            if (!handleRequestExternally(indexRequest)) {
                final IndexResponse indexResponse = handleRequest(indexRequest);
                if (indexResponse.status() != RestStatus.CREATED && indexResponse.status() != RestStatus.OK) {
                    throw new ElasticsearchException(
                            "Adding document to index '" + indexAlias + "' failed with response: " + indexResponse.status().getStatus());
                }
            }
        } catch (Exception e) {
            throw new ElasticsearchException("Failed to add document to index '" + indexAlias + "'!", e);
        }
    }

    @Override
    public Map<String, Boolean> addToIndex(String indexAlias, boolean applyIndexRefresh, boolean exceptionOnFailure, List<Map<String, Object>> docs) {
        Validate.notEmpty(indexAlias, "Parameter 'indexAlias' may not be empty!");

        final Map<String, Boolean> results = new HashMap<>();
        if (CollectionUtils.isEmpty(docs)) {
            return results;
        }

        final StopWatch sw = new StopWatch("index");
        try {
            BulkRequest bulkRequest = null;
            final int size = docs.size();
            for (int i = 0; i < size; i++) {
                if (bulkRequest == null) {
                    bulkRequest = new BulkRequest();
                    bulkRequest.setRefreshPolicy(getRefreshPolicy(applyIndexRefresh));
                }

                final Map<String, Object> doc = docs.get(i);
                final IndexRequest indexRequest = createIndexRequest(doc, indexAlias, false);
                bulkRequest.add(indexRequest);
                if (bulkRequest.numberOfActions() > indexingBulkSize || i == size - 1) {
                    if (!handleRequestExternally(bulkRequest)) {
                        LOG.debug("Adding {} documents to index '{}'.", bulkRequest.numberOfActions(), indexAlias);

                        sw.start("add");
                        final BulkResponse bulkResponse = handleRequest(bulkRequest);
                        LOG.debug("Bulk add response: {}", bulkResponse);
                        if (exceptionOnFailure && bulkResponse.hasFailures()) {
                            throw new ElasticsearchException("Add to index failed: " + bulkResponse.buildFailureMessage());
                        }
                        bulkResponse.forEach(itemResponse -> results.put(itemResponse.getId(), itemResponse.getFailure() == null));
                        sw.stop();
                    }
                    bulkRequest = null;
                }
            }
            LOG.debug("{}", new StopWatchPrettyPrint(sw));

            return results;
        } catch (Exception e) {
            throw new ElasticsearchException("Failed to add documents to index: indexAlias=" + indexAlias, e);
        }
    }

    @Override
    public void removeFromIndex(String indexAlias, boolean applyIndexRefresh, Object id) {
        Validate.notNull(indexAlias, "Parameter 'indexAlias' may not be null.");
        Validate.notNull(id, "Parameter 'id' may not be null.");

        final DeleteRequest deleteRequest = createDeleteRequest(id, indexAlias, applyIndexRefresh);
        if (!handleRequestExternally(deleteRequest)) {
            final DeleteResponse deleteResponse = handleRequest(deleteRequest);
            LOG.debug("Delete response: {}", deleteResponse);
        }
    }

    protected DeleteRequest createDeleteRequest(Object id, String indexAlias, boolean applyIndexRefresh) {
        final DeleteRequest deleteRequest = new DeleteRequest(indexAlias, idFormat.format(id)).setRefreshPolicy(getRefreshPolicy(applyIndexRefresh));
        LOG.debug("Created delete request: {}", deleteRequest);
        return deleteRequest;
    }

    @Override
    public void removeFromIndex(String indexAlias, boolean applyIndexRefresh, Collection<?> ids) {
        Validate.notNull(indexAlias, "Parameter 'indexAlias' may not be null!");

        if (CollectionUtils.isEmpty(ids)) {
            return;
        }

        final String[] idsAsArray = ids.stream().map(idFormat::format).toArray(String[]::new);

        final int maxSize = 10000;
        if (idsAsArray.length == 1) {
            removeFromIndex(indexAlias, applyIndexRefresh, idsAsArray[0]);
        } else {
            // Execution of the deletion in a loop to avoid OutOfMemory problems
            int count = 0;
            do {
                // Size of next bulk
                final int size = Math.min(maxSize, idsAsArray.length - count);
                final BulkRequest bulkRequest = new BulkRequest().setRefreshPolicy(getRefreshPolicy(applyIndexRefresh));
                for (int i = count; i < count + size; i++) {
                    bulkRequest.add(new DeleteRequest(indexAlias, idsAsArray[i]));
                }

                if (!handleRequestExternally(bulkRequest)) {
                    final BulkResponse bulkResponse = handleRequest(bulkRequest);
                    LOG.debug("Bulk remove response: {}", bulkResponse);
                }
                count += size;
            } while (count < idsAsArray.length);
        }
    }

    @Override
    public void removeFromIndex(QueryDto queryDto, MappingConfiguration mappingConfiguration, IndexPresetConfiguration indexPresetConfiguration,
                         boolean applyIndexRefresh) {
        Validate.notNull(indexPresetConfiguration, "Parameter 'indexPresetConfiguration' may not be null.");
        Validate.notNull(mappingConfiguration, "Parameter 'mappingConfiguration' may not be null.");
        Validate.notNull(queryDto, "Parameter 'queryDto' may not be null.");

        final InternalSearchRequest internalSearchRequest = searchRequest(indexPresetConfiguration, queryDto, mappingConfiguration);
        final DeleteByQueryRequest deleteByQueryRequest = new DeleteByQueryRequest(indexPresetConfiguration.getIndexAlias()).setRefresh(applyIndexRefresh);
        deleteByQueryRequest.getSearchRequest().source(internalSearchRequest.searchRequest.source());

        if (!handleRequestExternally(deleteByQueryRequest)) {
            LOG.debug("Delete by query request: {}", new SearchRequestSourceToString(internalSearchRequest.searchRequest));
            final BulkByScrollResponse response = handleRequest(deleteByQueryRequest);
            LOG.debug("Delete by query response: {}", response);
        }
    }

    @Override
    public boolean isServiceAvailable() {
        LOG.info("Check for cluster status YELLOW.");
        final boolean success = elasticsearchAdmin.waitForMinStatus(null, ClusterHealthStatus.YELLOW, checkClusterStatusTimeout);
        if (success) {
            LOG.info("Cluster is ok!");
        } else {
            LOG.error("Checking status of cluster failed!");
        }

        return success;
    }

    @Override
    public void refresh(String indexAlias) {
        final RefreshRequest request = new RefreshRequest(indexAlias);
        try {
            new RestClientIndexRefreshAction().action(restClient, request);
            restClient.indices().refresh(request, RequestOptions.DEFAULT);
        } catch (IOException e) {
            throw new RuntimeException("Failed to refresh the index '" + indexAlias + "'");
        }
    }

    @Override
    public String createIndex(IndexPresetConfiguration indexPresetConfiguration, MappingConfiguration mappingConfiguration) throws IndexCreateException {
        return elasticsearchAdmin.createIndex(indexPresetConfiguration, mappingConfiguration);
    }

    @Override
    public String createIndexWithAlias(IndexPresetConfiguration indexPresetConfiguration, MappingConfiguration mappingConfiguration)
            throws IndexCreateException, AliasCreateException, AliasAlreadyExistsException {
        return elasticsearchAdmin.createIndexWithAlias(indexPresetConfiguration, mappingConfiguration);
    }

    @Override
    public void deleteIndex(String indexName) {
        elasticsearchAdmin.deleteIndex(indexName);
    }

    @Override
    public void createAlias(String indexAlias, String indexName) throws AliasCreateException, AliasAlreadyExistsException {
        elasticsearchAdmin.createAlias(indexAlias, indexName);
    }

    @Override
    public String removeAlias(IndexPresetConfiguration indexPresetConfiguration) throws AliasHasMoreThanOneIndexException {
        return elasticsearchAdmin.removeAlias(indexPresetConfiguration.getIndexAlias());
    }

    @Override
    public boolean aliasExists(String indexAlias) {
        return elasticsearchAdmin.aliasOrIndexExists(indexAlias);
    }

    @Override
    public void setIndexVersion(String indexAlias, int indexVersion) {
        final Map<String, Object> document = DocumentBuilder.id(0, idFormat).put(INDEX_VERSION, indexVersion).build();
        addToIndex(indexAlias, true, document);
    }

    @Override
    public int getIndexVersion(String indexAlias) {
        final SearchSourceBuilder sourceBuilder = new SearchSourceBuilder().query(QueryBuilders.existsQuery(INDEX_VERSION)).docValueField(INDEX_VERSION);
        try {
            final SearchResponse searchResponse = restClient.search(new SearchRequest(indexAlias).source(sourceBuilder), RequestOptions.DEFAULT);
            for (final SearchHit searchHit : searchResponse.getHits().getHits()) {
                final DocumentField field = searchHit.getFields().get(INDEX_VERSION);
                if (field != null) {
                    final Object value = field.getValue();
                    if (value == null) {
                        return -1;
                    }
                    if (value instanceof Number) {
                        return ((Number) value).intValue();
                    }
                    return (int) value;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("failed get the index version.", e);
        }

        return -1;
    }

    @Override
    public List<String> resolveIndexNames(String indexAlias) {
        return elasticsearchAdmin.resolveIndexNames(indexAlias);
    }

    @Override
    public void updateMapping(IndexPresetConfiguration indexPresetConfiguration, MappingConfiguration mappingConfiguration,
                              List<FieldConfiguration> fieldConfigs) {
        elasticsearchAdmin.updateMapping(indexPresetConfiguration, mappingConfiguration, fieldConfigs);
    }

    @Override
    public ElasticsearchInfo getElasticsearchInfo() {

        final ElasticsearchInfo elasticsearchInfo = new ElasticsearchInfo();
        elasticsearchInfo.setClientVersion(Version.CURRENT.toString());
        try {
            final MainResponse mainResponse = restClient.info(RequestOptions.DEFAULT);
            elasticsearchInfo.setServerVersion(mainResponse.getVersion().getNumber());
            elasticsearchInfo.setClusterName(mainResponse.getClusterName());
        } catch (IOException ioe) {
            throw new RuntimeException("Getting elasticsearch info failed!", ioe);
        }
        return elasticsearchInfo;
    }

    @Override
    public SearchResultDto search(final QueryDto queryDto, final MappingConfiguration mappingConfiguration,
                                  IndexPresetConfiguration indexPresetConfiguration) {
        return new WatchedTask<SearchResultDto>(LOG, "search") {
            @Override
            public SearchResultDto process() {
                try {
                    final InternalSearchResponse internalSearchResponse = internalSearch(queryDto, mappingConfiguration, indexPresetConfiguration);
                    final SearchHits searchHits = internalSearchResponse.searchResponse.getHits();
                    final TotalHits totalHits = searchHits.getTotalHits();

                    final List<SearchHitDto> searchHitDtos = new ArrayList<>();
                    for (SearchHit hit : searchHits.getHits()) {
                        searchHitDtos.add(convertSearchHit(hit, mappingConfiguration));
                    }
                    final List<FacetDto> facetDtos = convertFacets(internalSearchResponse, queryDto, mappingConfiguration);

                    return new SearchResultDto(totalHits.value, totalHits.relation == TotalHits.Relation.EQUAL_TO, searchHitDtos, facetDtos);
                } catch (IndexMissingException e) {
                    throw new IndexMissingException(indexPresetConfiguration.getIndexAlias());
                }
            }
        }.getResult();
    }

    @Override
    public Map<String, Object> getDocument(String indexAlias, Object id) {
        Validate.notEmpty(indexAlias, "Parameter 'indexAlias' may not be null or empty!");
        Validate.notNull(id, "Parameter 'id' may not be null!");
        try {
            final GetRequest request = new GetRequest().index(indexAlias).id(idFormat.format(id));
            final GetResponse response = restClient.get(request, RequestOptions.DEFAULT);
            return response.getSource();
        } catch (Exception e) {
            throw new ElasticsearchException("Failed to get document: id=" + id, e);
        }
    }

    @Override
    public Map<String, List<String>> suggest(String indexAlias, SuggestExpression... expressions) {
        try {
            final SuggestBuilder suggestBuilder = new SuggestBuilder();
            for (final SuggestExpression expression : expressions) {
                suggestBuilder.addSuggestion(expression.getName(),
                        new CompletionSuggestionBuilder(expression.getName()).prefix(expression.getText()).size(expression.getCount()).skipDuplicates(true));
            }

            final SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder().suggest(suggestBuilder);
            final SearchRequest searchRequest = new SearchRequest(indexAlias).source(searchSourceBuilder);
            final SearchResponse searchResponse = new RestClientSearchAction().action(restClient, searchRequest);

            final Map<String, List<String>> result = new HashMap<>();
            for (final Suggest.Suggestion<? extends Suggest.Suggestion.Entry<? extends Suggest.Suggestion.Entry.Option>> suggestion
                    : searchResponse.getSuggest()) {
                final List<String> suggestedLines = result.computeIfAbsent(suggestion.getName(), name -> new ArrayList<>());
                for (final Suggest.Suggestion.Entry<? extends Suggest.Suggestion.Entry.Option> entry : suggestion.getEntries()) {
                    for (final Suggest.Suggestion.Entry.Option option : entry.getOptions()) {
                        suggestedLines.add(option.getText().string());
                    }
                }
            }
            return result;
        } catch (Exception e) {
            throw new ElasticsearchException("Failed to get suggestions: alias = " + indexAlias, e);
        }
    }

    @Override
    public QueryBuilder createQuery(SearchContext context) {
        for (QueryFactory queryFactory : queryFactories) {
            if (queryFactory.supports(context)) {
                final QueryBuilder result = queryFactory.create(this, context);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    protected SearchHitDto convertSearchHit(SearchHit hit, MappingConfiguration mappingConfiguration) {
        final Map<String, Object> source = hit.getSourceAsMap();
        final Map<String, DocumentField> fields = hit.getFields();
        final Map<String, Object> attributes = new HashMap<>();
        if (source != null) {
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                final String key = entry.getKey();
                final Object value = entry.getValue();
                attributes.put(key, value);
            }
        } else if (fields != null) {
            for (Map.Entry<String, DocumentField> field : fields.entrySet()) {
                final String key = field.getKey();
                final DocumentField documentField = field.getValue();
                final Object value = documentField.getValue();
                attributes.put(key, value);
            }
        } else {
            throw new RuntimeException("Missing data in search result!");
        }

        return new SearchHitDto(hit.getId(), attributes).innerHits(convertInnerHits(hit.getInnerHits(), mappingConfiguration));
    }

    protected Map<String, List<SearchHitDto>> convertInnerHits(Map<String, SearchHits> innerHits, MappingConfiguration mappingConfiguration) {
        if (MapUtils.isNotEmpty(innerHits)) {
            final Map<String, List<SearchHitDto>> convertedHits = new TreeMap<>();
            innerHits.forEach((name, hits) -> {
                for (final SearchHit hit : hits.getHits()) {
                    convertedHits.computeIfAbsent(name, k -> new ArrayList<>()).add(convertSearchHit(hit, mappingConfiguration));
                }
            });
            return convertedHits;
        } else {
            return null;
        }
    }

    protected List<FacetDto> convertFacets(InternalSearchResponse internalSearchResponse, QueryDto queryDto, MappingConfiguration mappingConfiguration) {
        final List<FacetDto> result = new ArrayList<>();

        if (internalSearchResponse.searchResponse.getAggregations() != null) {
            if (facetConverterChain != null) {
                final Locale locale = queryDto.getLocale();

                for (Aggregation aggregation : internalSearchResponse.searchResponse.getAggregations()) {
                    final FacetConverter facetConverter = facetConverterChain.getFirstResponsible(aggregation);
                    if (facetConverter != null) {
                        final String fieldName = internalSearchResponse.aggregationFields.get(aggregation.getName());
                        result.add(facetConverter.convert(aggregation, facetResolver(aggregation), fieldName, locale));
                    } else {
                        LOG.warn("Missing facet converter for aggregation: {}", aggregation);
                    }
                }
            } else {
                LOG.warn("Search response contains aggregations but facet converter chain is not defined!");
            }
        }
        return result;
    }

    protected FacetResolver facetResolver(Aggregation aggregation) {
        if (facetResolvers != null) {
            for (FacetResolver facetResolver : facetResolvers) {
                if (facetResolver.isResponsible(aggregation.getName())) {
                    return facetResolver;
                }
            }
        }
        return null;
    }

    protected InternalSearchResponse internalSearch(QueryDto queryDto, MappingConfiguration mappingConfiguration,
                                                    IndexPresetConfiguration indexPresetConfiguration) {
        final InternalSearchRequest internalSearchRequest = searchRequest(indexPresetConfiguration, queryDto, mappingConfiguration);
        final UUID queryId = UUID.randomUUID();
        QUERY_LOGGER.debug("Search request {}:\n{}\n{}", queryId, queryDto, new SearchRequestSourceToString(internalSearchRequest.searchRequest));

        final SearchResponse searchResponse;
        try {
            searchResponse = new RestClientSearchAction().action(restClient, internalSearchRequest.searchRequest);
        } catch (Exception e) {
            final ElasticExceptionCause cause = ElasticExceptionUtils.getCause(e);
            if (QUERY_SYNTAX == cause.getType()) {
                throw new QuerySyntaxException("Elasticsearch rest client search action failed: Failed to parse query!", cause.getMessage(), e);
            } else {
                throw new ElasticsearchException("Elasticsearch rest client search action failed!", e);
            }
        }

        QUERY_LOGGER.debug("Search response {}:\n{},", queryId, new SearchResponseToString(searchResponse));
        return new InternalSearchResponse(searchResponse, internalSearchRequest.aggregationFields);
    }

    @Override
    public String createQueryJson(QueryDto queryDto, MappingConfiguration mappingConfiguration, IndexPresetConfiguration indexPresetConfiguration,
                                  boolean pretty) {
        final InternalSearchRequest internalSearchRequest = searchRequest(indexPresetConfiguration, queryDto, mappingConfiguration);
        String json = internalSearchRequest.searchRequest.source().toString();
        if (pretty) {
            try {
                final ObjectMapper mapper = new ObjectMapper();
                final Object jsonObject = mapper.readValue(json, Object.class);
                json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
            } catch (JsonProcessingException e) {
                LOG.error("Failed to pretty format JSON string!", e);
            }
        }
        return json;
    }

    protected InternalSearchRequest searchRequest(IndexPresetConfiguration indexPresetConfiguration, QueryDto queryDto,
                                                  MappingConfiguration mappingConfiguration) {
        final SearchSourceBuilder searchSourceBuilder = searchSourceBuilder(queryDto, indexPresetConfiguration);

        final SearchContext context = new SearchContext(queryDto, mappingConfiguration);
        final QueryBuilder queryBuilder = createQuery(context);
        final QueryBuilder filterBuilder = createFilter(filterFactories, context);

        if (filterBuilder != null) {
            if (queryBuilder == null) {
                searchSourceBuilder.query(QueryBuilders.boolQuery().filter(filterBuilder));
            } else {
                searchSourceBuilder.query(QueryBuilders.boolQuery().must(queryBuilder).filter(filterBuilder));
            }
        } else if (queryBuilder != null) {
            searchSourceBuilder.query(queryBuilder);
        }

        addSortOptionsToSearchRequest(queryDto, mappingConfiguration, searchSourceBuilder);
        addCollapseOptionToSearchRequest(queryDto, mappingConfiguration, searchSourceBuilder);
        final Map<String, String> aggregationFields = addFacetsToSearchRequest(queryDto, mappingConfiguration, searchSourceBuilder);
        addFieldsToSearchRequest(queryDto, mappingConfiguration, searchSourceBuilder);

        final SearchRequest searchRequest = new SearchRequest(indexPresetConfiguration.getIndexAlias()).source(searchSourceBuilder);
        return new InternalSearchRequest(searchRequest, aggregationFields);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected Map<String, String> addFacetsToSearchRequest(QueryDto queryDto, MappingConfiguration mappingConfiguration,
                                                           SearchSourceBuilder searchRequestBuilder) {
        final Map<String, String> aggregationFields = new TreeMap<>();

        if (CollectionUtils.isNotEmpty(queryDto.getAggregations())) {
            for (SearchAggregation aggregation : queryDto.getAggregations()) {
                final AggregationBuilderFactory aggregationBuilderFactory = (aggregationBuilderFactoryRegistry != null)
                        ? aggregationBuilderFactoryRegistry.get(aggregation.getClass()) : null;
                final List<AggregationBuilder> aggregationBuilders = (aggregationBuilderFactory != null)
                        ? aggregationBuilderFactory.create(aggregation, mappingConfiguration, queryDto.getLocale())
                        : Collections.emptyList();
                aggregationBuilders.forEach(agg -> {
                        searchRequestBuilder.aggregation(agg);
                        if (agg instanceof ValuesSourceAggregationBuilder) {
                            aggregationFields.put(agg.getName(), ((ValuesSourceAggregationBuilder) agg).field());
                        }
                });
            }
        }
        return aggregationFields;
    }

    protected void addSortOptionsToSearchRequest(QueryDto queryDto, MappingConfiguration mappingConfig, SearchSourceBuilder searchRequestBuilder) {
        if (CollectionUtils.isNotEmpty(queryDto.getSortOptions())) {
            for (SortOption sortOption : queryDto.getSortOptions()) {
                searchRequestBuilder.sort(sortBuilder(sortOption, mappingConfig, queryDto.getLocale()));
            }
        }
    }

    protected SortBuilder<?> sortBuilder(SortOption sortOption, MappingConfiguration mappingConfig, Locale locale) {
        final String fieldName = sortOption.getFieldName();

        if (sortOption instanceof ScriptSortOption) {
            return scriptSortBuilder((ScriptSortOption) sortOption);
        } else if (SortOption.RELEVANCE_NAME.equals(fieldName)) {
            return SortBuilders.scoreSort();
        } else {
            return fieldSortBuilder(sortOption, mappingConfig, locale);
        }
    }

    protected ScriptSortBuilder scriptSortBuilder(ScriptSortOption scriptSortOption) {
        final ScriptDefinition scriptDefinition = scriptSortOption.getScriptDefinition();
        final ScriptType scriptType = ScriptType.valueOf(scriptDefinition.getScriptType().name());
        final Script script = new Script(scriptType, scriptDefinition.getLanguage(), scriptDefinition.getIdOrCode(), scriptDefinition.getOptions(),
                scriptDefinition.getParams());
        final ScriptSortBuilder.ScriptSortType sortType = ScriptSortBuilder.ScriptSortType.valueOf(scriptDefinition.getSortType().name());
        return new ScriptSortBuilder(script, sortType);
    }

    protected SortBuilder<?> fieldSortBuilder(SortOption sortOption, MappingConfiguration mappingConfig, Locale locale) {
        final String fieldName = sortOption.getFieldName();
        SortBuilder<?> sortBuilder;

        FieldConfiguration fieldConfiguration = fieldConfiguration(mappingConfig, fieldName, false);
        final String topFieldName = StringUtils.substringBefore(fieldName, ".");

        if (fieldConfiguration == null) {
            fieldConfiguration = fieldConfiguration(mappingConfig, topFieldName, false);
        }

        sortBuilder = null;
        if (fieldConfiguration != null) {
            if (fieldConfiguration.getParent() != null) {
                fieldConfiguration = fieldConfiguration.getParent();
            }

            if (fieldConfiguration.isNestedObject()) {
                sortBuilder = buildNestedSort(fieldConfiguration, fieldName, sortOption, mappingConfig, locale);
            } else if (isTextField(fieldConfiguration)) {
                sortBuilder = buildStringSort(fieldConfiguration, mappingConfig, fieldName, sortOrder(sortOption), locale);
            }
        } else {
            LOG.warn("Missing field configuration for field '{}', sorting by this field may not be possible.", fieldName);
        }

        if (sortBuilder == null) {
            sortBuilder = SortBuilders.fieldSort(topFieldName).order(sortOrder(sortOption)).sortMode(sortMode(sortOption)).missing(sortMissing());
        }

        return sortBuilder;
    }

    private FieldSortBuilder buildNestedSort(FieldConfiguration fieldConfiguration, String nestedFieldName, SortOption sortOption,
                                             MappingConfiguration mappingConfiguration, Locale locale) {
        final FieldConfiguration nestedField = fieldConfiguration.getNestedField(StringUtils.substringAfter(nestedFieldName, "."));
        final String sortFieldName = sortFieldName(nestedField, nestedFieldName);
        return SortBuilders
                .fieldSort(sortFieldName)
                .order(sortOrder(sortOption))
                .missing(sortMissing())
                .sortMode(sortMode(sortOption))
                .setNestedSort(nestedSortBuilder(fieldConfiguration.getName(), sortOption, mappingConfiguration, locale));
    }

    private NestedSortBuilder nestedSortBuilder(String topFieldName, SortOption sortOption, MappingConfiguration mappingConfiguration, Locale locale) {
        final NestedSortBuilder nestedSortBuilder = new NestedSortBuilder(topFieldName);
        if (sortOption.getFilter() != null) {
            nestedSortBuilder.setFilter(
                    createFilter(filterFactories, new SearchContext(QueryDto.sortFilter(sortOption.getFilter(), locale), mappingConfiguration)));
        }
        return nestedSortBuilder;
    }

    private SortOrder sortOrder(SortOption sortOption) {
        return (sortOption.getSortDirection() == SortOption.Direction.ASC) ? SortOrder.ASC : SortOrder.DESC;
    }

    private SortMode sortMode(SortOption sortOption) {
        if (sortOption.getArrayMode() == SortOption.ArrayMode.DEFAULT) {
            return (sortOption.getSortDirection() == SortOption.Direction.ASC) ? SortMode.MIN : SortMode.MAX;
        } else {
            return SortMode.valueOf(sortOption.getArrayMode().name());
        }
    }

    private String sortMissing() {
        return "_" + missingValueSortPosition.getValue();
    }

    protected FieldSortBuilder buildStringSort(FieldConfiguration fieldConfiguration, MappingConfiguration mappingConfiguration, String fieldName,
                                             SortOrder sortOrder, Locale locale) {
        if (fieldConfiguration.isSortable()) {
            final String esFieldName = FieldConfigurationUtils.getElasticFieldName(mappingConfiguration, fieldName, locale);
            final String sortFieldName = sortFieldName(fieldConfiguration, esFieldName);
            return SortBuilders
                    .fieldSort(sortFieldName)
                    .missing("_" + missingValueSortPosition.getValue())
                    .order(sortOrder);
        } else {
            throw new RuntimeException("The field '" + fieldConfiguration.getName() + "' is not configured as sortable!");
        }
    }

    protected void addCollapseOptionToSearchRequest(QueryDto queryDto, MappingConfiguration mappingConfig, SearchSourceBuilder searchSourceBuilder) {
        final CollapseOption collapseOption = queryDto.getCollapseOption();
        if (collapseOption != null) {
            searchSourceBuilder.collapse(collapseBuilder(collapseOption, mappingConfig, queryDto.getLocale()));
        }
    }

    protected CollapseBuilder collapseBuilder(CollapseOption collapseOption, MappingConfiguration mappingConfig, Locale locale) {
        final CollapseBuilder collapseBuilder = new CollapseBuilder(collapseOption.getField());
        if (CollectionUtils.isNotEmpty(collapseOption.getInnerHitsOptions())) {
            final List<InnerHitBuilder> innerHitBuilders = collapseOption.getInnerHitsOptions().stream()
                    .map(innerHitsOption -> innerHitBuilder(innerHitsOption, mappingConfig, locale))
                    .collect(Collectors.toList());
            collapseBuilder.setInnerHits(innerHitBuilders);
        }
        return collapseBuilder;
    }

    protected InnerHitBuilder innerHitBuilder(InnerHitsOption innerHitsOption, MappingConfiguration mappingConfig, Locale locale) {
        final InnerHitBuilder innerHitBuilder
                = new InnerHitBuilder(innerHitsOption.getName()).setSize(innerHitsOption.getSize()).setFrom(innerHitsOption.getFrom());
        if (CollectionUtils.isNotEmpty(innerHitsOption.getSortOptions())) {
            innerHitsOption.getSortOptions().forEach(sortOption -> innerHitBuilder.addSort(sortBuilder(sortOption, mappingConfig, locale)));
        }
        if (innerHitsOption.getCollapseOption() != null) {
            innerHitBuilder.setInnerCollapse(collapseBuilder(innerHitsOption.getCollapseOption(), mappingConfig, locale));
        }
        return innerHitBuilder;
    }

    protected void addFieldsToSearchRequest(QueryDto queryDto, MappingConfiguration mappingConfiguration, SearchSourceBuilder searchRequestBuilder) {
        final List<String> fields = queryDto.getFieldsToResolve();
        if (CollectionUtils.isEmpty(fields)) {
            return;
        }

        validateFields(queryDto, mappingConfiguration);

        switch (queryDto.getFieldResolverType()) {
            case DOC_VALUES:
                addDocValuesToSearchRequest(fields, searchRequestBuilder, mappingConfiguration);
                break;
            case SOURCE_VALUES:
                addSourceValuesToSearchRequest(fields, searchRequestBuilder);
                break;
        }
    }

    protected void validateFields(QueryDto queryDto, MappingConfiguration mappingConfiguration) {
        final Map<String, FieldConfiguration> fieldConfigurations = new HashMap<>(mappingConfiguration.getFieldConfigurations().size());
        for (final FieldConfiguration fieldConfiguration : mappingConfiguration.getFieldConfigurations()) {
            fieldConfigurations.put(fieldConfiguration.getName(), fieldConfiguration);
        }

        for (final String field : queryDto.getFieldsToResolve()) {
            final FieldConfiguration fieldConfiguration = fieldConfigurations.get(field);
            if (fieldConfiguration == null) {
                throw new RuntimeException("Undefined field to resolve: " + field);
            }
        }
    }

    protected void addDocValuesToSearchRequest(List<String> fields, SearchSourceBuilder searchRequestBuilder, MappingConfiguration mappingConfiguration) {
        searchRequestBuilder.fetchSource(false);
        for (final String field : fields) {
            final String docValueField = keywordFieldName(fieldConfiguration(mappingConfiguration, field), field);
            searchRequestBuilder.docValueField(docValueField);
        }
    }

    protected void addSourceValuesToSearchRequest(List<String> fields, SearchSourceBuilder searchRequestBuilder) {
        final String[] includes = fields.toArray(new String[0]);
        final String[] excludes = new String[0];
        searchRequestBuilder.fetchSource(includes, excludes);
    }

    protected SearchSourceBuilder searchSourceBuilder(QueryDto queryDto, IndexPresetConfiguration indexPresetConfiguration) {
        final QueryRangeDto queryRangeDto = queryDto.getQueryRange();
        final SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        if (queryRangeDto != null) {
            searchSourceBuilder.from(queryRangeDto.getStart()).size(queryRangeDto.getLimit());
            if (queryRangeDto.getMaxTrackTotalHits() != null) {
                searchSourceBuilder.trackTotalHitsUpTo(queryRangeDto.getMaxTrackTotalHits().intValue());
            }
        } else {
            searchSourceBuilder.size(indexPresetConfiguration.getMaxResultWindow());
        }
        return searchSourceBuilder;
    }

    protected IndexRequest createIndexRequest(Map<String, Object> doc, String indexAlias, boolean applyIndexRefresh) {
        final XContentBuilder contentBuilder;
        try {
            contentBuilder = XContentFactory.jsonBuilder();
            contentBuilder.startObject();
            addToIndexRequestContent(contentBuilder, doc);
            contentBuilder.endObject();
        } catch (IOException e) {
            throw new ElasticsearchException("Failed to create index request: indexAlias=" + indexAlias, e);
        }
        final String id = getId(doc);
        final IndexRequest indexRequest = new IndexRequest(indexAlias).id(id).source(contentBuilder).setRefreshPolicy(getRefreshPolicy(applyIndexRefresh));
        LOG.debug("Created index request: {}", indexRequest);
        return indexRequest;
    }

    @SuppressWarnings("unchecked")
    protected void addToIndexRequestContent(XContentBuilder contentBuilder, Map<String, Object> doc) throws IOException {
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            if (entry.getValue() != null) {
                final String fieldName = entry.getKey();
                if (entry.getValue() instanceof List) {
                    final List<?> list = (List<?>) entry.getValue();
                    if (list.size() > 0 && list.get(0) instanceof Map) {
                        final List<Map<String, Object>> nestedObjectList = (List<Map<String, Object>>) list;
                        addNestedObjectsToIndexRequestContent(contentBuilder, fieldName, nestedObjectList);
                    } else {
                        final List<?> trimmedList = StringTrimUtility.trimListValues((List<?>) entry.getValue());
                        contentBuilder.array(fieldName, trimmedList.toArray());
                    }
                } else if (entry.getValue() instanceof Date) {
                    contentBuilder.field(fieldName, ElasticDateUtils.formatIso((Date) entry.getValue(), timeZone));
                } else if (entry.getValue() instanceof Boolean) {
                    contentBuilder.field(fieldName, entry.getValue());
                } else if (entry.getValue() instanceof String && (entry.getValue().equals(Boolean.TRUE.toString())
                        || entry.getValue().equals(Boolean.FALSE.toString()))) {
                    contentBuilder.field(fieldName, Boolean.valueOf((String) entry.getValue()));
                } else if (entry.getValue() instanceof String) {
                    final String stringValue = (String) entry.getValue();
                    contentBuilder.field(fieldName, stringValue.trim());
                } else {
                    contentBuilder.field(fieldName, entry.getValue());
                }
            }
        }
    }

    protected void addNestedObjectsToIndexRequestContent(XContentBuilder contentBuilder, String fieldName, List<Map<String, Object>> nestedObjectList)
            throws IOException {
        contentBuilder.startArray(fieldName);
        for (final Map<String, Object> doc : nestedObjectList) {
            contentBuilder.startObject();
            addToIndexRequestContent(contentBuilder, doc);
            contentBuilder.endObject();
        }
        contentBuilder.endArray();
    }

    protected boolean handleRequestExternally(WriteRequest<?> request) {
        return writeRequestHandler != null && writeRequestHandler.handle(request);
    }

    protected boolean handleRequestExternally(AbstractBulkByScrollRequest<?> request) {
        return writeRequestHandler != null && writeRequestHandler.handle(request);
    }

    @SuppressWarnings("unchecked")
    protected <Req extends WriteRequest<Req>, Resp> Resp handleRequest(WriteRequest<Req> request) {
        if (request instanceof IndexRequest) {
            return (Resp) new RestClientIndexAction().action(restClient, (IndexRequest) request);
        } else if (request instanceof DeleteRequest) {
            return (Resp) new RestClientDeleteAction().action(restClient, (DeleteRequest) request);
        } else if (request instanceof BulkRequest) {
            return (Resp) new RestClientBulkAction().action(restClient, (BulkRequest) request);
        } else {
            throw new RuntimeException("Unsupported request type: " + request.getClass().getName());
        }
    }

    protected BulkByScrollResponse handleRequest(AbstractBulkByScrollRequest<?> request) {
        if (request instanceof UpdateByQueryRequest) {
            return new RestClientUpdateByQueryAction().action(restClient, (UpdateByQueryRequest) request);
        } else if (request instanceof DeleteByQueryRequest) {
            return new RestClientDeleteByQueryAction().action(restClient, (DeleteByQueryRequest) request);
        } else {
            throw new RuntimeException("Unsupported request type: " + request.getClass().getName());
        }
    }

    protected static class InternalSearchRequest {
        final SearchRequest searchRequest;
        final Map<String, String> aggregationFields;

        public InternalSearchRequest(SearchRequest searchRequest, Map<String, String> aggregationFields) {
            this.searchRequest = searchRequest;
            this.aggregationFields = aggregationFields;
        }
    }

    protected static class InternalSearchResponse {
        final SearchResponse searchResponse;
        final Map<String, String> aggregationFields;

        public InternalSearchResponse(SearchResponse searchResponse, Map<String, String> aggregationFields) {
            this.searchResponse = searchResponse;
            this.aggregationFields = aggregationFields;
        }
    }
}
