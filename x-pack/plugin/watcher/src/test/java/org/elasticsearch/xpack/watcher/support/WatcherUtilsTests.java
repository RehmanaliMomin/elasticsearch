/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.watcher.support;

import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.watcher.support.WatcherUtils;
import org.elasticsearch.xpack.watcher.support.search.WatcherSearchTemplateRequest;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.xpack.core.watcher.support.WatcherDateTimeUtils.formatDate;
import static org.elasticsearch.xpack.core.watcher.support.WatcherUtils.flattenModel;
import static org.elasticsearch.xpack.watcher.input.search.ExecutableSearchInput.DEFAULT_SEARCH_TYPE;
import static org.elasticsearch.xpack.watcher.support.search.WatcherSearchTemplateRequest.DEFAULT_INDICES_OPTIONS;
import static org.elasticsearch.xpack.watcher.test.WatcherTestUtils.getRandomSupportedSearchType;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class WatcherUtilsTests extends ESTestCase {

    private static final String IGNORE_THROTTLED_FIELD_WARNING = "Deprecated field [ignore_throttled] used, this field is unused and " +
        "will be removed entirely";

    public void testFlattenModel() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(Clock.systemUTC());
        Map<String, Object> map = new HashMap<>();
        map.put("a", singletonMap("a1", new int[] { 0, 1, 2 }));
        map.put("b", new String[] { "b0", "b1", "b2" });
        map.put("c", Arrays.asList(TimeValue.timeValueSeconds(0), TimeValue.timeValueSeconds(1)));
        map.put("d", now);

        Map<String, Object> result = flattenModel(map);
        assertThat(result.size(), is(9));
        assertThat(result, hasEntry("a.a1.0", "0"));
        assertThat(result, hasEntry("a.a1.1", "1"));
        assertThat(result, hasEntry("a.a1.2", "2"));
        assertThat(result, hasEntry("b.0", "b0"));
        assertThat(result, hasEntry("b.1", "b1"));
        assertThat(result, hasEntry("b.2", "b2"));
        assertThat(result, hasEntry("c.0", "0"));
        assertThat(result, hasEntry("c.1", "1000"));
        assertThat(result, hasEntry("d", formatDate(now)));
    }

    public void testResponseToData() throws Exception {
        final Map<String, Object> expected = new HashMap<>();
        expected.put("key1", "val");
        expected.put("key2", 1);
        expected.put("key3", 1.4);
        expected.put("key4", Arrays.asList("a", "b", "c"));
        Map<String, Object> otherMap = new HashMap<>();
        otherMap.putAll(expected);
        expected.put("key5", otherMap);
        ToXContentObject content = (builder, params) -> {
            builder.startObject();
            for (Map.Entry<String, ?> entry : expected.entrySet()) {
                builder.field(entry.getKey());
                builder.value(entry.getValue());
            }
            builder.endObject();
            return builder;
        };
        Map<String, Object> result = WatcherUtils.responseToData(content, ToXContent.EMPTY_PARAMS);
        assertThat(result, equalTo(expected));
    }

    public void testSerializeSearchRequest() throws Exception {
        String[] expectedIndices = generateRandomStringArray(5, 5, true);
        IndicesOptions expectedIndicesOptions = IndicesOptions.fromOptions(randomBoolean(), randomBoolean(), randomBoolean(),
            randomBoolean(), randomBoolean(), DEFAULT_INDICES_OPTIONS.allowAliasesToMultipleIndices(),
            DEFAULT_INDICES_OPTIONS.forbidClosedIndices(), DEFAULT_INDICES_OPTIONS.ignoreAliases(),
            DEFAULT_INDICES_OPTIONS.ignoreThrottled());
        SearchType expectedSearchType = getRandomSupportedSearchType();

        BytesReference expectedSource = null;
        Script expectedTemplate = null;
        WatcherSearchTemplateRequest request;
        boolean stored = false;
        if (randomBoolean()) {
            Map<String, Object> params = new HashMap<>();
            if (randomBoolean()) {
                int maxParams = randomIntBetween(1, 10);
                for (int i = 0; i < maxParams; i++) {
                    params.put(randomAlphaOfLengthBetween(1, 5), randomAlphaOfLengthBetween(1, 5));
                }
            }
            String text = randomAlphaOfLengthBetween(1, 5);
            ScriptType scriptType = randomFrom(ScriptType.values());
            stored = scriptType == ScriptType.STORED;
            expectedTemplate = new Script(scriptType, stored ? null : "mustache", text, params);
            request = new WatcherSearchTemplateRequest(expectedIndices, expectedSearchType,
                    expectedIndicesOptions, expectedTemplate);
        } else {
            SearchSourceBuilder sourceBuilder = SearchSourceBuilder.searchSource().query(QueryBuilders.matchAllQuery()).size(11);
            XContentBuilder builder = jsonBuilder();
            builder.value(sourceBuilder);
            expectedSource = BytesReference.bytes(builder);
            request = new WatcherSearchTemplateRequest(expectedIndices, expectedSearchType,
                    expectedIndicesOptions, expectedSource);
        }

        XContentBuilder builder = jsonBuilder();
        request.toXContent(builder, ToXContent.EMPTY_PARAMS);
        XContentParser parser = createParser(builder);
        assertThat(parser.nextToken(), equalTo(XContentParser.Token.START_OBJECT));
        WatcherSearchTemplateRequest result = WatcherSearchTemplateRequest.fromXContent(parser, DEFAULT_SEARCH_TYPE);

        assertThat(result.getIndices(), arrayContainingInAnyOrder(expectedIndices != null ? expectedIndices : new String[0]));
        assertThat(result.getIndicesOptions(), equalTo(expectedIndicesOptions));
        assertThat(result.getSearchType(), equalTo(expectedSearchType));

        assertNotNull(result.getTemplate());
        assertThat(result.getTemplate().getLang(), equalTo(stored ? null : "mustache"));
        if (expectedIndicesOptions.equals(DEFAULT_INDICES_OPTIONS) == false) {
            assertWarnings(IGNORE_THROTTLED_FIELD_WARNING);
        }
        if (expectedSource == null) {
            assertThat(result.getTemplate().getIdOrCode(), equalTo(expectedTemplate.getIdOrCode()));
            assertThat(result.getTemplate().getType(), equalTo(expectedTemplate.getType()));
            assertThat(result.getTemplate().getParams(), equalTo(expectedTemplate.getParams()));
        } else {
            assertThat(result.getTemplate().getIdOrCode(), equalTo(expectedSource.utf8ToString()));
            assertThat(result.getTemplate().getType(), equalTo(ScriptType.INLINE));
        }
    }

    public void testDeserializeSearchRequest() throws Exception {

        XContentBuilder builder = jsonBuilder().startObject();

        String[] indices = Strings.EMPTY_ARRAY;
        if (randomBoolean()) {
            indices = generateRandomStringArray(5, 5, false);
            if (randomBoolean()) {
                builder.array("indices", indices);
            } else {
                builder.field("indices", Strings.arrayToCommaDelimitedString(indices));
            }
        }

        IndicesOptions indicesOptions = DEFAULT_INDICES_OPTIONS;
        if (randomBoolean()) {
            indicesOptions = IndicesOptions.fromOptions(randomBoolean(), randomBoolean(), randomBoolean(),
                randomBoolean(), randomBoolean(), indicesOptions.allowAliasesToMultipleIndices(),
                indicesOptions.forbidClosedIndices(), indicesOptions.ignoreAliases(), indicesOptions.ignoreThrottled());
            if (indicesOptions.equals(DEFAULT_INDICES_OPTIONS) == false) {
                builder.startObject("indices_options");
                indicesOptions.toXContent(builder, ToXContent.EMPTY_PARAMS);
                builder.endObject();
            }
        }

        SearchType searchType = SearchType.DEFAULT;
        if (randomBoolean()) {
            searchType = getRandomSupportedSearchType();
            builder.field("search_type", randomBoolean() ? searchType.name() : searchType.name().toLowerCase(Locale.ROOT));
        }

        BytesReference source = BytesArray.EMPTY;
        if (randomBoolean()) {
            SearchSourceBuilder searchSourceBuilder = SearchSourceBuilder.searchSource().query(QueryBuilders.matchAllQuery()).size(11);
            XContentBuilder searchSourceJsonBuilder = jsonBuilder();
            searchSourceBuilder.toXContent(searchSourceJsonBuilder, ToXContent.EMPTY_PARAMS);
            source = XContentHelper.toXContent(searchSourceBuilder, XContentType.JSON, false);
            builder.rawField("body", source.streamInput());
        }
        Script template = null;
        boolean stored = false;
        if (randomBoolean()) {
            Map<String, Object> params = new HashMap<>();
            if (randomBoolean()) {
                int maxParams = randomIntBetween(1, 10);
                for (int i = 0; i < maxParams; i++) {
                    params.put(randomAlphaOfLengthBetween(1, 5), randomAlphaOfLengthBetween(1, 5));
                }
            }
            String text = randomAlphaOfLengthBetween(1, 5);
            ScriptType scriptType = randomFrom(ScriptType.values());
            stored = scriptType == ScriptType.STORED;
            template = new Script(scriptType, stored ? null : "mustache", text, params);
            builder.field("template", template);
        }
        builder.endObject();

        XContentParser parser = createParser(builder);
        assertThat(parser.nextToken(), equalTo(XContentParser.Token.START_OBJECT));
        WatcherSearchTemplateRequest result = WatcherSearchTemplateRequest.fromXContent(parser, DEFAULT_SEARCH_TYPE);

        if (indicesOptions.equals(DEFAULT_INDICES_OPTIONS) == false) {
            assertWarnings(IGNORE_THROTTLED_FIELD_WARNING);
        }
        assertThat(result.getIndices(), arrayContainingInAnyOrder(indices));
        assertThat(result.getIndicesOptions(), equalTo(indicesOptions));
        assertThat(result.getSearchType(), equalTo(searchType));
        if (source == null) {
            assertThat(result.getSearchSource(), nullValue());
        } else {
            assertThat(result.getSearchSource().utf8ToString(), equalTo(source.utf8ToString()));
        }
        if (template == null) {
            assertThat(result.getTemplate(), nullValue());
        } else {
            assertThat(result.getTemplate().getIdOrCode(), equalTo(template.getIdOrCode()));
            assertThat(result.getTemplate().getType(), equalTo(template.getType()));
            assertThat(result.getTemplate().getParams(), equalTo(template.getParams()));
            assertThat(result.getTemplate().getLang(), equalTo(stored ? null : "mustache"));
        }
    }
}
