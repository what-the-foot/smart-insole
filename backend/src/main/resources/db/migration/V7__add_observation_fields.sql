-- rule-v1.2.0 observation fields. analysis_results.valid_step_count already exists (V4).
-- All columns are nullable so results stored by earlier algorithm versions stay readable as-is.
ALTER TABLE analysis_patterns
    ADD COLUMN observation_level VARCHAR(24) NULL,
    ADD COLUMN occurrence_rate DOUBLE NULL,
    ADD COLUMN observed_count INT NULL,
    ADD COLUMN window_count INT NULL;

ALTER TABLE analysis_results
    ADD COLUMN observation_summary_json JSON NULL;
