/*
 * Copyright 2020 picturesafe media/data/bank GmbH
 */

package de.picturesafe.search.elasticsearch.impl;

import de.picturesafe.search.elasticsearch.DataChangeProcessingMode;
import de.picturesafe.search.elasticsearch.DocumentProvider;
import de.picturesafe.search.elasticsearch.FieldConfigurationProvider;
import de.picturesafe.search.elasticsearch.IndexInitializationListener;
import de.picturesafe.search.elasticsearch.api.RangeFacet;
import de.picturesafe.search.elasticsearch.config.FieldConfiguration;
import de.picturesafe.search.elasticsearch.config.IndexPresetConfiguration;
import de.picturesafe.search.elasticsearch.config.LanguageSortConfiguration;
import de.picturesafe.search.elasticsearch.config.MappingConfiguration;
import de.picturesafe.search.elasticsearch.connect.Elasticsearch;
import de.picturesafe.search.elasticsearch.connect.ElasticsearchResult;
import de.picturesafe.search.elasticsearch.connect.dto.FacetDto;
import de.picturesafe.search.elasticsearch.connect.dto.FacetEntryDto;
import de.picturesafe.search.elasticsearch.connect.dto.QueryDto;
import de.picturesafe.search.elasticsearch.connect.dto.QueryFacetDto;
import de.picturesafe.search.elasticsearch.connect.dto.QueryFilterDto;
import de.picturesafe.search.elasticsearch.connect.dto.QueryRangeDto;
import de.picturesafe.search.elasticsearch.connect.error.AliasAlreadyExistsException;
import de.picturesafe.search.elasticsearch.error.ElasticsearchServiceException;
import de.picturesafe.search.elasticsearch.model.AccountContext;
import de.picturesafe.search.elasticsearch.model.ElasticsearchInfo;
import de.picturesafe.search.elasticsearch.model.ResultFacet;
import de.picturesafe.search.elasticsearch.model.ResultFacetItem;
import de.picturesafe.search.elasticsearch.model.ResultRangeFacetItem;
import de.picturesafe.search.elasticsearch.model.SearchResult;
import de.picturesafe.search.elasticsearch.model.SearchResultItem;
import de.picturesafe.search.elasticsearch.model.SuggestResult;
import de.picturesafe.search.expression.Expression;
import de.picturesafe.search.expression.SuggestExpression;
import de.picturesafe.search.parameter.AggregationField;
import de.picturesafe.search.parameter.SearchParameter;
import de.picturesafe.search.util.logging.StopWatchPrettyPrint;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.LocaleUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static de.picturesafe.search.elasticsearch.DataChangeProcessingMode.BACKGROUND;

@Component
@SuppressWarnings("unused")
public class ElasticsearchServiceImpl implements InternalElasticsearchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchServiceImpl.class);
    protected static final int DEFAULT_MAX_PAGE_SIZE = 2000;
    protected static final int DEFAULT_SHARD_SIZE_FACTOR = 5;

    protected final Elasticsearch elasticsearch;
    protected final FieldConfigurationProvider fieldConfigurationProvider;
    protected final Map<String, IndexPresetConfiguration> indexPresetConfigurationByAlias;

    protected final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();
    protected final IndexRequestCache indexRequestCache = new IndexRequestCache();

    @Autowired(required = false)
    protected DocumentProvider documentProvider;

    @Value("${elasticsearch.service.max_page_size:" + DEFAULT_MAX_PAGE_SIZE + "}")
    protected int maxPageSize = DEFAULT_MAX_PAGE_SIZE;

    @Value("${elasticsearch.service.shard_size_factor:" + DEFAULT_SHARD_SIZE_FACTOR + "}")
    protected int shardSizeFactor = DEFAULT_SHARD_SIZE_FACTOR;

    @Value("${elasticsearch.service.optimize_expressions.enabled:true}")
    protected boolean optimizeExpressionsEnabled = true;

    @Autowired
    public ElasticsearchServiceImpl(Elasticsearch elasticsearch,
                                    List<IndexPresetConfiguration> indexPresetConfigurations,
                                    FieldConfigurationProvider fieldConfigurationProvider) {
        this.elasticsearch = elasticsearch;
        this.fieldConfigurationProvider = fieldConfigurationProvider;
        indexPresetConfigurationByAlias = new TreeMap<>();
        for (final IndexPresetConfiguration conf : indexPresetConfigurations) {
            indexPresetConfigurationByAlias.put(conf.getIndexAlias(), conf);
        }
    }

    /**
     * Sets the {@link DocumentProvider}
     *
     * @param documentProvider {@link DocumentProvider}
     */
    public void setDocumentProvider(DocumentProvider documentProvider) {
        this.documentProvider = documentProvider;
    }

    /**
     * Sets the max allowed page size to be retrieved.
     *
     * @param maxPageSize   The max allowed page size to be retrieved
     */
    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    /**
     * Sets if expressions should be optimized.
     *
     * @see Expression#optimize()
     * @see SearchParameter#setOptimizeExpressions(boolean)
     *
     * @param optimizeExpressionsEnabled true if expressions should be optimized
     */
    public void setOptimizeExpressionsEnabled(boolean optimizeExpressionsEnabled) {
        this.optimizeExpressionsEnabled = optimizeExpressionsEnabled;
    }

    /**
     * Sets the shard size factor.
     * <p>
     * Term aggregation: The higher the requested size is, the more accurate the results will be, but also, the more expensive
     * it will be to compute the final results The shard_size parameter can be used to minimize the extra work that comes with
     * bigger requested size. When defined, it will determine how many terms the coordinating node will request from each shard.
     * Once all the shards responded, the coordinating node will then reduce them to a final result which
     * will be based on the size parameter - this way, one can increase the accuracy of the returned terms and avoid the overhead
     * of streaming a big list of buckets back to the client.
     * <p>
     * shard_size = size * shardSizeFactor
     *
     * @param shardSizeFactor   The shard size factor
     */
    public void setShardSizeFactor(int shardSizeFactor) {
        this.shardSizeFactor = shardSizeFactor;
    }

    @Override
    public ElasticsearchInfo getElasticsearchInfo() {
        return elasticsearch.getElasticsearchInfo();
    }

    @Override
    public void createAndInitializeIndex(String indexAlias, boolean rebuildIfExists, IndexInitializationListener listener,
                                         DataChangeProcessingMode dataChangeProcessingMode) {
        Validate.notEmpty(indexAlias, "Parameter 'indexName' may not be null or empty!");
        Validate.notNull(dataChangeProcessingMode, "Parameter 'dataChangeProcessingMode' may not be null!");
        Validate.notNull(documentProvider, "DocumentProvider must be set when using index initialization feature!");

        if (aliasExists(indexAlias) && !rebuildIfExists) {
            throw new AliasAlreadyExistsException("Elasticsearch alias already exists: " + indexAlias);
        }
        if (dataChangeProcessingMode == BACKGROUND) {
            executor(indexAlias).submit(() -> indexInitializer().init(indexAlias, listener));
        } else {
            indexInitializer().init(indexAlias, listener);
        }
    }

    @Override
    public boolean aliasExists(String indexAlias) {
        return elasticsearch.aliasExists(indexAlias);
    }

    @Override
    public String createIndex(String indexAlias) {
        LOGGER.info("Creating a new elasticsearch index for alias '{}'", indexAlias);
        final IndexPresetConfiguration indexPresetConfiguration = getIndexPresetConfiguration(indexAlias);
        final MappingConfiguration mappingConfiguration = getMappingConfiguration(indexAlias, true);
        final String indexName = elasticsearch.createIndex(indexPresetConfiguration, mappingConfiguration);
        LOGGER.info("New elasticsearch index '{}' was created for alias '{}'", indexName, indexAlias);
        return indexName;
    }

    @Override
    public String createIndexWithAlias(String indexAlias) {
        LOGGER.info("Creating a new elasticsearch index with alias '{}'", indexAlias);
        final IndexPresetConfiguration indexPresetConfiguration = getIndexPresetConfiguration(indexAlias);
        final MappingConfiguration mappingConfiguration = getMappingConfiguration(indexAlias, true);
        final String indexName = elasticsearch.createIndexWithAlias(indexPresetConfiguration, mappingConfiguration);
        LOGGER.info("New elasticsearch index '{}' was created with alias '{}'", indexName, indexAlias);
        return indexName;
    }

    @Override
    public void addFieldConfiguration(String indexAlias, FieldConfiguration... fieldConfigs) {
        final IndexPresetConfiguration indexPresetConfiguration = getIndexPresetConfiguration(indexAlias);
        final MappingConfiguration mappingConfiguration = getMappingConfiguration(indexAlias, false);
        elasticsearch.updateMapping(indexPresetConfiguration, mappingConfiguration, Arrays.asList(fieldConfigs));
    }

    @Override
    public void deleteIndex(String indexName) {
        LOGGER.info("Deleting elasticsearch index: {}", indexName);
        elasticsearch.deleteIndex(indexName);
    }

    @Override
    public void deleteIndexWithAlias(String indexAlias) {
        LOGGER.info("Deleting elasticsearch indexes for alias: {}", indexAlias);
        for (String indexName : elasticsearch.resolveIndexNames(indexAlias)) {
            elasticsearch.deleteIndex(indexName);
        }
        removeAlias(indexAlias);
    }

    @Override
    public List<String> resolveIndexNames(String indexAlias) {
        try {
            return elasticsearch.resolveIndexNames(indexAlias);
        } catch (Exception e) {
            throw new ElasticsearchServiceException("Failed to resolve index names for alis: " + indexAlias, e);
        }
    }

    @Override
    public void createAlias(String indexAlias, String indexName) {
        LOGGER.info("Creating elasticsearch alias '{}' for index '{}'", indexAlias, indexName);
        elasticsearch.createAlias(indexAlias, indexName);
    }

    @Override
    public String removeAlias(String indexAlias) {
        LOGGER.info("Removing elasticsearch alias '{}'", indexAlias);
        final IndexPresetConfiguration indexPresetConfiguration = getIndexPresetConfiguration(indexAlias);
        return elasticsearch.removeAlias(indexPresetConfiguration);
    }

    @Override
    public void setIndexVersion(String indexAlias, int indexVersion) {
        final MappingConfiguration mappingConfiguration = getMappingConfiguration(indexAlias, false);
        elasticsearch.setIndexVersion(indexAlias, indexVersion, mappingConfiguration);
    }

    @Override
    public int getIndexVersion(String indexAlias) {
        return elasticsearch.getIndexVersion(indexAlias);
    }

    @Override
    public void addToIndex(String indexAlias, DataChangeProcessingMode dataChangeProcessingMode, Map<String, Object> document) {
        Validate.notEmpty(indexAlias, "Parameter 'indexAlias' may not be null or empty!");
        Validate.notNull(dataChangeProcessingMode, "Parameter 'dataChangeProcessingMode' may not be null!");
        Validate.notNull(document, "Parameter 'document' may not be null!");

        elasticsearch.addToIndex(document, getMappingConfiguration(indexAlias, true), indexAlias, dataChangeProcessingMode.isRefresh());
        indexRequestCache.put(indexAlias, IndexRequest.add(document));
    }

    @Override
    public void addToIndex(String indexAlias, DataChangeProcessingMode dataChangeProcessingMode, List<Map<String, Object>> documents) {
        Validate.notEmpty(indexAlias, "Parameter 'indexName' may not be null or empty!");
        Validate.notNull(dataChangeProcessingMode, "Parameter 'dataChangeProcessingMode' may not be null!");
        Validate.notNull(documents, "Parameter 'documents' may not be null!");

        addToIndex(getMappingConfiguration(indexAlias, true), indexAlias, dataChangeProcessingMode, documents);
        indexRequestCache.put(indexAlias, IndexRequest.add(documents));
    }

    @Override
    public void addToIndex(MappingConfiguration mappingConfiguration, String indexName, DataChangeProcessingMode dataChangeProcessingMode,
                           List<Map<String, Object>> documents) {
        elasticsearch.addToIndex(documents, mappingConfiguration, indexName, dataChangeProcessingMode.isRefresh(), true);
    }

    @Override
    public void removeFromIndex(String indexAlias, DataChangeProcessingMode dataChangeProcessingMode, long id) {
        Validate.notEmpty(indexAlias, "Parameter 'indexName' may not be null or empty!");
        Validate.notNull(dataChangeProcessingMode, "Parameter 'dataChangeProcessingMode' may not be null!");

        final IndexPresetConfiguration indexPresetConfiguration = getIndexPresetConfiguration(indexAlias);
        elasticsearch.removeFromIndex(getMappingConfiguration(indexAlias, false), indexPresetConfiguration, dataChangeProcessingMode.isRefresh(), id);
        indexRequestCache.put(indexAlias, IndexRequest.remove(id));
    }

    @Override
    public void removeFromIndex(String indexAlias, DataChangeProcessingMode dataChangeProcessingMode, Collection<Long> ids) {
        Validate.notEmpty(indexAlias, "Parameter 'indexName' may not be null or empty!");
        Validate.notNull(dataChangeProcessingMode, "Parameter 'dataChangeProcessingMode' may not be null!");
        Validate.notNull(ids, "Parameter 'ids' may not be null!");

        final IndexPresetConfiguration indexPresetConfiguration = getIndexPresetConfiguration(indexAlias);
        elasticsearch.removeFromIndex(getMappingConfiguration(indexAlias, false), indexPresetConfiguration, dataChangeProcessingMode.isRefresh(), ids);
        indexRequestCache.put(indexAlias, IndexRequest.remove(ids));
    }

    @Override
    public SearchResult search(String indexAlias, AccountContext accountContext, Expression expression, SearchParameter searchParameter) {
        Validate.notEmpty(indexAlias, "Parameter 'indexName' may not be null or empty!");

        final StopWatch sw = new StopWatch();

        final int pageSize = getPageSize(searchParameter);
        final ElasticsearchResult elasticsearchResult = getElasticSearchResult(indexAlias, accountContext, expression, searchParameter, pageSize, sw);

        final List<Map<String, Object>> searchResult = elasticsearchResult.getHits();
        final List<SearchResultItem> resultItems = new ArrayList<>();
        for (Map<String, Object> hit : searchResult) {
            resultItems.add(new SearchResultItem(hit));
        }

        sw.start("get max results");
        final long totalHitCount = elasticsearchResult.getTotalHitCount();
        final int resultCount = getMaxResults(indexAlias, searchParameter.getMaxResults(), totalHitCount);
        sw.stop();

        LOGGER.debug("Performed search on index '{}':\n{}", indexAlias, new StopWatchPrettyPrint(sw));
        final int pageIndex = (searchParameter.getPageIndex() != null) ? searchParameter.getPageIndex() : 1;
        return new SearchResult(resultItems, pageIndex, pageSize, resultCount, totalHitCount, elasticsearchResult.isExactCount(),
                convertFacets(elasticsearchResult.getFacetDtoList()));
    }

    @Override
    public MappingConfiguration getMappingConfiguration(String indexAlias) {
        return getMappingConfiguration(indexAlias, true);
    }

    @Override
    public Map<String, Object> getDocument(String indexAlias, long id) {
        return elasticsearch.getDocument(indexAlias, id);
    }

    @Override
    public SuggestResult suggest(String indexAlias, SuggestExpression... expressions) {
        Validate.notEmpty(indexAlias, "Parameter 'indexAlias' may not be null or empty!");
        Validate.notEmpty(expressions, "Parameter 'expressions' may not be null or empty!");
        return new SuggestResult(elasticsearch.suggest(indexAlias, expressions));
    }

    protected IndexPresetConfiguration getIndexPresetConfiguration(String indexAlias) {
        return indexPresetConfigurationByAlias.get(indexAlias);
    }

    protected ExecutorService executor(String indexAlias) {
        return executors.computeIfAbsent(indexAlias, ElasticsearchServiceImpl::buildThreadExecutor);
    }

    protected static ThreadPoolExecutor buildThreadExecutor(String indexAlias) {
        return new ThreadPoolExecutor(
                0,
                1,
                10L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                buildThreadFactoryForAlias(indexAlias)
        );
    }

    protected static BasicThreadFactory buildThreadFactoryForAlias(String indexAlias) {
        return new BasicThreadFactory.Builder()
                .namingPattern("indexer-pool-" + indexAlias + "-worker-%d")
                .build();
    }

    protected IndexInitializer indexInitializer() {
        return new IndexInitializer(this, documentProvider, indexRequestCache);
    }

    protected int getPageSize(SearchParameter searchParameter) {
        if (searchParameter == null) {
            return maxPageSize;
        }
        int pageSize = (searchParameter.getPageSize() != null) ? searchParameter.getPageSize() : maxPageSize;
        if (searchParameter.getMaxResults() != null) {
            pageSize = Math.min(pageSize, searchParameter.getMaxResults());
        }
        return pageSize;
    }

    protected ElasticsearchResult getElasticSearchResult(String indexAlias, AccountContext accountContext, Expression expression,
                                                         SearchParameter searchParameter, int pageSize, StopWatch sw) {
        if (searchParameter == null) {
            searchParameter = new SearchParameter();
        }
        final IndexPresetConfiguration indexPresetConfiguration = getIndexPresetConfiguration(indexAlias);

        sw.start("create query");
        final int pageIndex = (searchParameter.getPageIndex() != null) ? searchParameter.getPageIndex() : 1;
        final int start = (pageIndex - 1) * pageSize;
        final int maxResults = (searchParameter.getMaxResults() != null) ? searchParameter.getMaxResults() : indexPresetConfiguration.getMaxResultWindow();
        final int resultLimit = Math.min(pageSize, maxResults - start);

        final QueryDto queryDto = createQueryDto(accountContext, expression, start, resultLimit, searchParameter);
        sw.stop();

        sw.start("process search");
        final ElasticsearchResult result = elasticsearch.search(queryDto, getMappingConfiguration(indexAlias, true), indexPresetConfiguration);
        sw.stop();

        return result;
    }

    protected QueryDto createQueryDto(AccountContext accountContext, Expression expression, int start, int limit, SearchParameter searchParameter) {
        Validate.notNull(accountContext, "Parameter 'accountContext' may not be null!");
        Validate.notNull(expression, "Parameter 'expression' may not be null!");
        Validate.notNull(searchParameter, "Parameter 'searchParameter' may not be null!");

        if (optimizeExpressionsEnabled && searchParameter.isOptimizeExpressions()) {
            expression = expression.optimize();
        }
        final QueryRangeDto queryRangeDto = new QueryRangeDto(start, limit, searchParameter.getMaxTrackTotalHits());

        final List<QueryFilterDto> queryFilterDtos = new ArrayList<>();
        final List<QueryFacetDto> queryFacetDtos = new ArrayList<>();
        final List<AggregationField> aggregationFields = searchParameter.getAggregationFields();
        if (!CollectionUtils.isEmpty(aggregationFields)) {
            for (AggregationField aggregationField : aggregationFields) {
                final int defaultMaxCount = searchParameter.getDefaultAggregationMaxCount();
                int maxCount = aggregationField.getMaxCount();
                if (maxCount <= 0 || maxCount > defaultMaxCount) {
                    maxCount = defaultMaxCount;
                }
                final QueryFacetDto facetDto = new QueryFacetDto(aggregationField.getName(), maxCount, maxCount * shardSizeFactor);
                queryFacetDtos.add(facetDto);
            }
        }

        final List<String> fieldsToResolve = searchParameter.getFieldsToResolve();
        final QueryDto.FieldResolverType fieldResolverType = QueryDto.FieldResolverType.SOURCE_VALUES;
        final Locale locale = StringUtils.isNotBlank(searchParameter.getLanguage())
                ? LocaleUtils.toLocale(searchParameter.getLanguage())
                : accountContext.getCurrentLoginLanguage();
        return new QueryDto(
                expression, queryRangeDto, queryFilterDtos, searchParameter.getSortOptions(), queryFacetDtos,
                locale, fieldsToResolve, fieldResolverType);
    }

    protected int getMaxResults(String indexAlias, Integer maxResults, long totalHitCount) {
        if (maxResults == null) {
            final IndexPresetConfiguration indexPresetConfiguration = getIndexPresetConfiguration(indexAlias);
            return (int) Math.min(totalHitCount, indexPresetConfiguration.getMaxResultWindow());
        }
        return (int) Math.min(totalHitCount, (long) maxResults);
    }

    protected List<ResultFacet> convertFacets(List<FacetDto> facets) {
        return facets.stream().map(this::convertFacet).collect(Collectors.toList());
    }

    protected ResultFacet convertFacet(FacetDto facetDto) {
        final List<ResultFacetItem> facetItems = facetDto.getFacetEntryDtos().stream().map(this::convertFacetItem).collect(Collectors.toList());
        return new ResultFacet(facetDto.getName(), facetDto.getCount(), facetItems);
    }

    protected ResultFacetItem convertFacetItem(FacetEntryDto entryDto) {
        return entryDto instanceof RangeFacet ? new ResultRangeFacetItem((RangeFacet) entryDto) : new ResultFacetItem(entryDto);
    }

    protected MappingConfiguration getMappingConfiguration(String indexAlias, boolean addFieldConfigurations) {
        final List<LanguageSortConfiguration> languageSortConfigurations = new ArrayList<>();
        for (final Locale locale : fieldConfigurationProvider.getSupportedLocales()) {
            languageSortConfigurations.add(new LanguageSortConfiguration(locale));
        }
        return new MappingConfiguration(
                addFieldConfigurations ? fieldConfigurationProvider.getFieldConfigurations(indexAlias) : Collections.emptyList(), languageSortConfigurations);
    }
}