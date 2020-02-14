/*
 * Copyright 2020 picturesafe media/data/bank GmbH
 */

package de.picturesafe.search.elasticsearch.connect;

import de.picturesafe.search.elasticsearch.config.IndexPresetConfiguration;
import de.picturesafe.search.elasticsearch.config.RestClientConfiguration;
import de.picturesafe.search.elasticsearch.config.impl.StandardIndexPresetConfiguration;
import de.picturesafe.search.elasticsearch.connect.impl.ElasticsearchAdminImpl;
import de.picturesafe.search.elasticsearch.connect.impl.ElasticsearchImpl;
import de.picturesafe.search.spring.configuration.TestConfiguration;
import org.apache.commons.lang3.time.DateUtils;
import org.elasticsearch.client.RestHighLevelClient;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {TestConfiguration.class, ElasticsearchImpl.class, ElasticsearchAdminImpl.class})
public abstract class AbstractElasticIntegrationTest {

    private static final AtomicInteger INSTANCE_COUNT = new AtomicInteger();
    static final String STANDARD_DATE_FORMAT = "dd.MM.yyyy";

    @Autowired
    private RestClientConfiguration elasticsearchRestClientConfiguration;

    @Autowired
    @Qualifier("indexPresetConfiguration")
    protected IndexPresetConfiguration indexPresetConfiguration;

    @Autowired
    @Qualifier("elasticsearchTimeZone")
    protected String tz;

    protected final String indexAlias = getClass().getSimpleName().toLowerCase(Locale.ROOT) + "-" + INSTANCE_COUNT.incrementAndGet();
    RestHighLevelClient restClient;
    private TimeZone tzBeforeTest;

    @Before
    public final void baseSetup() {
        tzBeforeTest = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(tz));
        this.restClient = elasticsearchRestClientConfiguration.getClient();
        indexPresetConfiguration = new StandardIndexPresetConfiguration(indexPresetConfiguration, indexAlias);
    }

    @After
    public final void tearDown() {
        TimeZone.setDefault(tzBeforeTest);
    }

    public static Date today() {
        return DateUtils.truncate(new Date(), Calendar.DAY_OF_MONTH);
    }

    public static Date tomorrow() {
        return DateUtils.addDays(today(), 1);
    }

    public static Date yesterday() {
        return DateUtils.addDays(today(), -1);
    }
}
