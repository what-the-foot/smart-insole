-- rule-v1.4.0 (contract 1.3.0) IMU shank movement summary, DEC-036.
-- The whole MovementSummary object (imuCoverage, referenceMethod, left, right) is stored as one JSON document;
-- no history projection reads it, so no scalar columns are needed (unlike V8).
-- NULL for results computed by earlier algorithm versions and for sessions without IMU frames (DEC-023).
ALTER TABLE analysis_results
    ADD COLUMN movement_summary_json JSON NULL;
