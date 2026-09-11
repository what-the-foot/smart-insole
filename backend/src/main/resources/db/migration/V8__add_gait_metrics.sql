-- rule-v1.3.0 (contract 1.2.0) session-level gait metrics, DEC-035.
-- Stored as plain columns so the history list projection can join analysis_results without JSON parsing.
-- All columns are nullable: results stored by earlier algorithm versions stay readable and return null.
-- pressure_distribution_json keeps carrying the full PressureDistribution (including the load share
-- fields) and the existing cadence/contact/symmetry columns are unchanged.
ALTER TABLE analysis_results
    ADD COLUMN left_load_share_pct DOUBLE NULL,
    ADD COLUMN right_load_share_pct DOUBLE NULL,
    ADD COLUMN left_stride_time_ms DOUBLE NULL,
    ADD COLUMN right_stride_time_ms DOUBLE NULL,
    ADD COLUMN mean_stride_time_ms DOUBLE NULL;
