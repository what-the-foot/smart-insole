-- Firmware sensor order S01..S08 (index = S number - 1 = MUX channel) in the DEC-019 foot-local
-- coordinate system (x 0 = medial -> 1 = lateral, y 0 = toe -> 1 = heel). Coordinates are proposed
-- values pending the mechanical drawing (+-0.05 adjustment expected).
INSERT INTO sensor_layouts(version, sensor_count, points_json, active, created_at) VALUES
('layout-s01s08-v1', 8, '[{"index":0,"label":"S01","x":0.40,"y":0.88,"region":"HEEL","medialLateral":"MEDIAL"},{"index":1,"label":"S02","x":0.62,"y":0.88,"region":"HEEL","medialLateral":"LATERAL"},{"index":2,"label":"S03","x":0.36,"y":0.62,"region":"MIDFOOT","medialLateral":"MEDIAL"},{"index":3,"label":"S04","x":0.66,"y":0.62,"region":"MIDFOOT","medialLateral":"LATERAL"},{"index":4,"label":"S05","x":0.32,"y":0.36,"region":"FOREFOOT","medialLateral":"MEDIAL"},{"index":5,"label":"S06","x":0.50,"y":0.34,"region":"FOREFOOT","medialLateral":"CENTER"},{"index":6,"label":"S07","x":0.70,"y":0.38,"region":"FOREFOOT","medialLateral":"LATERAL"},{"index":7,"label":"S08","x":0.36,"y":0.12,"region":"TOE","medialLateral":"MEDIAL"}]', TRUE, UTC_TIMESTAMP(6));

-- Legacy layouts stay for the devices.sensor_layout_version foreign key and remain readable through
-- GET /api/v1/sensor-layouts/{version}; only new registrations are blocked. No 6-sensor seed is
-- created until the hardware decides which two sensors are dropped.
UPDATE sensor_layouts SET active = FALSE WHERE version IN ('layout-v1', 'layout-v1-6');
