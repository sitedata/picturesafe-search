package de.picturesafe.search.elasticsearch.impl;

import de.picturesafe.search.elasticsearch.DataChangeProcessingMode;
import de.picturesafe.search.elasticsearch.ElasticsearchService;
import de.picturesafe.search.elasticsearch.SingleIndexElasticsearchService;
import de.picturesafe.search.elasticsearch.config.FieldConfiguration;
import de.picturesafe.search.elasticsearch.config.IndexPresetConfiguration;
import de.picturesafe.search.elasticsearch.model.AccountContext;
import de.picturesafe.search.elasticsearch.model.ElasticsearchInfo;
import de.picturesafe.search.elasticsearch.model.SearchResult;
import de.picturesafe.search.elasticsearch.model.SuggestResult;
import de.picturesafe.search.expression.Expression;
import de.picturesafe.search.expression.SuggestExpression;
import de.picturesafe.search.parameter.SearchParameter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
@SuppressWarnings("unused")
public class SingleIndexElasticsearchServiceImpl implements SingleIndexElasticsearchService {

    protected ElasticsearchService elasticsearchService;
    protected IndexPresetConfiguration indexPresetConfiguration;
    protected String indexAlias;

    @Autowired
    public SingleIndexElasticsearchServiceImpl(ElasticsearchService elasticsearchService,
                                               IndexPresetConfiguration indexPresetConfiguration) {
        this.elasticsearchService = elasticsearchService;
        this.indexPresetConfiguration = indexPresetConfiguration;
        this.indexAlias = indexPresetConfiguration.getIndexAlias();
    }

    public ElasticsearchService getElasticsearchService() {
        return elasticsearchService;
    }

    @Override
    public ElasticsearchInfo getElasticsearchInfo() {
        return elasticsearchService.getElasticsearchInfo();
    }

    @Override
    public String getIndexAlias() {
        return indexAlias;
    }

    @Override
    public String getIndexName() {
        return elasticsearchService.resolveIndexNames(indexAlias).get(0);
    }

    @Override
    public String createIndexWithAlias() {
        return elasticsearchService.createIndexWithAlias(indexAlias);
    }

    @Override
    public void deleteIndexWithAlias() {
        elasticsearchService.deleteIndexWithAlias(indexAlias);
    }

    @Override
    public void setIndexVersion(int indexVersion) {
        elasticsearchService.setIndexVersion(indexAlias, indexVersion);
    }

    @Override
    public int getIndexVersion() {
        return elasticsearchService.getIndexVersion(indexAlias);
    }

    @Override
    public void addFieldConfiguration(FieldConfiguration... fieldConfigs) {
        elasticsearchService.addFieldConfiguration(indexAlias, fieldConfigs);
    }

    @Override
    public void addToIndex(DataChangeProcessingMode dataChangeProcessingMode, Map<String, Object> document) {
        elasticsearchService.addToIndex(indexAlias, dataChangeProcessingMode, document);
    }

    @Override
    public void addToIndex(DataChangeProcessingMode dataChangeProcessingMode, List<Map<String, Object>> documents) {
        elasticsearchService.addToIndex(indexAlias, dataChangeProcessingMode, documents);
    }

    @Override
    public void removeFromIndex(DataChangeProcessingMode dataChangeProcessingMode, long id) {
        elasticsearchService.removeFromIndex(indexAlias, dataChangeProcessingMode, id);
    }

    @Override
    public void removeFromIndex(DataChangeProcessingMode dataChangeProcessingMode, Collection<Long> ids) {
        elasticsearchService.removeFromIndex(indexAlias, dataChangeProcessingMode, ids);
    }

    @Override
    public SearchResult search(AccountContext accountContext, Expression expression, SearchParameter searchParameter) {
        return elasticsearchService.search(indexAlias, accountContext, expression, searchParameter);
    }

    @Override
    public Map<String, Object> getDocument(long id) {
        return elasticsearchService.getDocument(indexAlias, id);
    }

    @Override
    public SuggestResult suggest(SuggestExpression... expressions) {
        return elasticsearchService.suggest(indexAlias, expressions);
    }
}