package com.smartinsole.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Receiver ingestion tunables.
 *
 * @param maxFramesPerBatch          upper bound of frames per Frame Batch request (contract: 200)
 * @param sequenceWrapSuspectDistance a frame whose sequence is at least this far below the last accepted
 *                                    sequence while its deviceTimeMs still advances raises
 *                                    SEQUENCE_WRAP_SUSPECTED; disabled for protocolVersion 2 frames
 * @param sampleRateMismatchTolerance relative deviation of the median deviceTimeMs delta from the session
 *                                    sample period that raises SAMPLE_RATE_MISMATCH (0.30 = 30 %)
 * @param retryAfterSeconds           Retry-After value sent with a 409 SESSION_NOT_MEASURING RETRY disposition
 */
@ConfigurationProperties("app.ingestion")
public record IngestionProperties(
        int maxFramesPerBatch,
        long sequenceWrapSuspectDistance,
        double sampleRateMismatchTolerance,
        int retryAfterSeconds
) {
    public IngestionProperties {
        if (maxFramesPerBatch < 1 || maxFramesPerBatch > 200) {
            throw new IllegalArgumentException("app.ingestion.max-frames-per-batch must be between 1 and 200");
        }
        if (sequenceWrapSuspectDistance < 1) {
            throw new IllegalArgumentException("app.ingestion.sequence-wrap-suspect-distance must be positive");
        }
        if (sampleRateMismatchTolerance <= 0 || sampleRateMismatchTolerance >= 1) {
            throw new IllegalArgumentException(
                    "app.ingestion.sample-rate-mismatch-tolerance must be between 0 and 1 (exclusive)");
        }
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("app.ingestion.retry-after-seconds must be positive");
        }
    }
}
