-- Frame Batch schemaVersion 1.1 metadata (all nullable: 1.0 batches leave them NULL).
ALTER TABLE pressure_frames
    ADD COLUMN protocol_version INT NULL,
    ADD COLUMN receiver_received_at TIMESTAMP(6) NULL,
    ADD COLUMN data_mode VARCHAR(16) NULL,
    ADD COLUMN calibrated BOOLEAN NULL,
    ADD COLUMN imu_available BOOLEAN NULL,
    ADD COLUMN accel_x_mg INT NULL,
    ADD COLUMN accel_y_mg INT NULL,
    ADD COLUMN accel_z_mg INT NULL,
    ADD COLUMN gyro_x_dps10 INT NULL,
    ADD COLUMN gyro_y_dps10 INT NULL,
    ADD COLUMN gyro_z_dps10 INT NULL,
    ADD COLUMN flags INT NULL;

-- Device ADC scale (registration only allows 4095) and last heartbeat battery values.
ALTER TABLE devices
    ADD COLUMN adc_max INT NOT NULL DEFAULT 4095,
    ADD COLUMN last_battery_percent DOUBLE NULL,
    ADD COLUMN last_battery_mv INT NULL;

-- Devices registered before V5 were validated and normalized on the legacy 16-bit scale.
-- Keep that scale for them so existing SIMULATED data stays reproducible; the literal lives only here.
UPDATE devices SET adc_max = 65535;

-- Session snapshot of the device ADC scale plus the receiver upload status report.
ALTER TABLE measurement_sessions
    ADD COLUMN adc_max INT NOT NULL DEFAULT 4095,
    ADD COLUMN receiver_id VARCHAR(100) NULL,
    ADD COLUMN receiver_state VARCHAR(24) NULL,
    ADD COLUMN receiver_pending_batches INT NULL,
    ADD COLUMN receiver_observed_at TIMESTAMP(6) NULL;

UPDATE measurement_sessions SET adc_max = 65535;

-- First accepted sequence per side enables O(1) gap accounting: gaps = (last - first + 1) - received.
ALTER TABLE measurement_quality_stats
    ADD COLUMN first_left_sequence BIGINT NULL,
    ADD COLUMN first_right_sequence BIGINT NULL;
