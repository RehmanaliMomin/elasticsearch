/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.ml.inference.trainedmodel;

import org.elasticsearch.Version;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.core.ml.inference.InferenceConfigItemTestCase;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

public class ZeroShotClassificationConfigTests extends InferenceConfigItemTestCase<ZeroShotClassificationConfig> {

    @Override
    protected boolean supportsUnknownFields() {
        return true;
    }

    @Override
    protected Predicate<String> getRandomFieldsExcludeFilter() {
        return field -> field.isEmpty() == false;
    }

    @Override
    protected ZeroShotClassificationConfig doParseInstance(XContentParser parser) throws IOException {
        return ZeroShotClassificationConfig.fromXContentLenient(parser);
    }

    @Override
    protected Writeable.Reader<ZeroShotClassificationConfig> instanceReader() {
        return ZeroShotClassificationConfig::new;
    }

    @Override
    protected ZeroShotClassificationConfig createTestInstance() {
        return createRandom();
    }

    @Override
    protected ZeroShotClassificationConfig mutateInstanceForVersion(ZeroShotClassificationConfig instance, Version version) {
        return instance;
    }

    public static ZeroShotClassificationConfig createRandom() {
        return new ZeroShotClassificationConfig(
            randomFrom(List.of("entailment", "neutral", "contradiction"), List.of("contradiction", "neutral", "entailment")),
            randomBoolean() ? null : VocabularyConfigTests.createRandom(),
            randomBoolean() ? null : BertTokenizationTests.createRandom(),
            randomAlphaOfLength(10),
            randomBoolean(),
            randomBoolean() ? null : randomList(1, 5, () -> randomAlphaOfLength(10)),
            randomBoolean() ? null : randomAlphaOfLength(7)
        );
    }
}
