ALTER TABLE measurement_quality_stats
    ADD COLUMN last_left_device_time_ms BIGINT NULL,
    ADD COLUMN last_right_device_time_ms BIGINT NULL;

CREATE INDEX idx_patterns_code_result
    ON analysis_patterns(pattern_code, analysis_result_id);

