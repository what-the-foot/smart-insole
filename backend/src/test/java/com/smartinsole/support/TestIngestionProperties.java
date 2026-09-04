package com.smartinsole.support;

import com.smartinsole.global.config.IngestionProperties;

/** application.yml defaults for {@link IngestionProperties} in unit tests. */
public final class TestIngestionProperties {
    private TestIngestionProperties() {
    }

    public static IngestionProperties defaults() {
        return new IngestionProperties(200, 60_000, 0.30, 2);
    }

    public static IngestionProperties withWrapDistance(long distance) {
        return new IngestionProperties(200, distance, 0.30, 2);
    }
}
