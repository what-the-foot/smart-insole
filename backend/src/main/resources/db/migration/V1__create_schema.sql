CREATE TABLE users (
    id CHAR(36) PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    name VARCHAR(50) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE sensor_layouts (
    version VARCHAR(50) PRIMARY KEY,
    sensor_count SMALLINT NOT NULL,
    points_json JSON NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE devices (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    serial_number VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    foot_side VARCHAR(16) NOT NULL,
    sensor_count SMALLINT NOT NULL,
    sensor_layout_version VARCHAR(50) NOT NULL,
    firmware_version VARCHAR(50) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_seen_at TIMESTAMP(6) NULL,
    registered_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_devices_serial UNIQUE (serial_number),
    CONSTRAINT fk_devices_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_devices_layout FOREIGN KEY (sensor_layout_version) REFERENCES sensor_layouts(version),
    INDEX idx_devices_owner_side_status (user_id, foot_side, status),
    INDEX idx_devices_last_seen (last_seen_at)
);

CREATE TABLE calibration_profiles (
    id CHAR(36) PRIMARY KEY,
    device_id CHAR(36) NOT NULL,
    version VARCHAR(50) NOT NULL,
    baseline_values_json JSON NOT NULL,
    scale_values_json JSON NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_calibration_device_version UNIQUE (device_id, version),
    CONSTRAINT fk_calibration_device FOREIGN KEY (device_id) REFERENCES devices(id)
);

CREATE TABLE measurement_sessions (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    left_device_id CHAR(36) NOT NULL,
    right_device_id CHAR(36) NOT NULL,
    left_calibration_id CHAR(36) NOT NULL,
    right_calibration_id CHAR(36) NOT NULL,
    left_sensor_layout_version VARCHAR(50) NOT NULL,
    right_sensor_layout_version VARCHAR(50) NOT NULL,
    status VARCHAR(24) NOT NULL,
    sample_rate_hz SMALLINT NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    memo VARCHAR(500) NULL,
    data_quality_score SMALLINT NULL,
    started_at TIMESTAMP(6) NULL,
    ended_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_sessions_left_device FOREIGN KEY (left_device_id) REFERENCES devices(id),
    CONSTRAINT fk_sessions_right_device FOREIGN KEY (right_device_id) REFERENCES devices(id),
    CONSTRAINT fk_sessions_left_calibration FOREIGN KEY (left_calibration_id) REFERENCES calibration_profiles(id),
    CONSTRAINT fk_sessions_right_calibration FOREIGN KEY (right_calibration_id) REFERENCES calibration_profiles(id),
    INDEX idx_sessions_owner_created (user_id, created_at DESC),
    INDEX idx_sessions_status_updated (status, updated_at)
);

CREATE TABLE pressure_frames (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id CHAR(36) NOT NULL,
    device_id CHAR(36) NOT NULL,
    foot_side VARCHAR(16) NOT NULL,
    sequence_no BIGINT NOT NULL,
    device_time_ms BIGINT NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    sensor_1 INT NOT NULL,
    sensor_2 INT NOT NULL,
    sensor_3 INT NOT NULL,
    sensor_4 INT NOT NULL,
    sensor_5 INT NOT NULL,
    sensor_6 INT NOT NULL,
    sensor_7 INT NULL,
    sensor_8 INT NULL,
    CONSTRAINT uk_frames_session_device_sequence UNIQUE (session_id, device_id, sequence_no),
    CONSTRAINT fk_frames_session FOREIGN KEY (session_id) REFERENCES measurement_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_frames_device FOREIGN KEY (device_id) REFERENCES devices(id),
    INDEX idx_frames_session_device_time (session_id, device_time_ms),
    INDEX idx_frames_session_side_sequence (session_id, foot_side, sequence_no),
    INDEX idx_frames_device_received (device_id, received_at)
);

CREATE TABLE measurement_quality_stats (
    session_id CHAR(36) PRIMARY KEY,
    expected_frame_count BIGINT NOT NULL DEFAULT 0,
    received_frame_count BIGINT NOT NULL DEFAULT 0,
    duplicate_frame_count BIGINT NOT NULL DEFAULT 0,
    rejected_frame_count BIGINT NOT NULL DEFAULT 0,
    sequence_gap_count BIGINT NOT NULL DEFAULT 0,
    missing_frame_rate DOUBLE NOT NULL DEFAULT 0,
    flags_json JSON NOT NULL,
    score SMALLINT NOT NULL DEFAULT 100,
    level VARCHAR(16) NOT NULL DEFAULT 'GOOD',
    last_left_sequence BIGINT NULL,
    last_right_sequence BIGINT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_quality_session FOREIGN KEY (session_id) REFERENCES measurement_sessions(id) ON DELETE CASCADE
);

CREATE TABLE analysis_jobs (
    id CHAR(36) PRIMARY KEY,
    session_id CHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    algorithm_version VARCHAR(50) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(100) NULL,
    error_message VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_job_session_algorithm UNIQUE (session_id, algorithm_version),
    CONSTRAINT fk_job_session FOREIGN KEY (session_id) REFERENCES measurement_sessions(id) ON DELETE CASCADE
);

CREATE TABLE analysis_results (
    id CHAR(36) PRIMARY KEY,
    session_id CHAR(36) NOT NULL,
    algorithm_version VARCHAR(50) NOT NULL,
    quality_score SMALLINT NOT NULL,
    quality_level VARCHAR(16) NOT NULL,
    missing_frame_rate DOUBLE NOT NULL,
    cadence DOUBLE NOT NULL,
    left_contact_time_ms DOUBLE NOT NULL,
    right_contact_time_ms DOUBLE NOT NULL,
    symmetry_index DOUBLE NOT NULL,
    pressure_distribution_json JSON NOT NULL,
    quality_flags_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_result_session_algorithm UNIQUE (session_id, algorithm_version),
    CONSTRAINT fk_result_session FOREIGN KEY (session_id) REFERENCES measurement_sessions(id) ON DELETE CASCADE
);

CREATE TABLE analysis_patterns (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    analysis_result_id CHAR(36) NOT NULL,
    pattern_code VARCHAR(100) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    evidence VARCHAR(1000) NOT NULL,
    sort_order INT NOT NULL,
    CONSTRAINT fk_pattern_result FOREIGN KEY (analysis_result_id) REFERENCES analysis_results(id) ON DELETE CASCADE,
    INDEX idx_pattern_result_order (analysis_result_id, sort_order)
);

CREATE TABLE recommendations (
    code VARCHAR(100) PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    instructions_json JSON NOT NULL,
    duration_minutes INT NOT NULL,
    caution_text VARCHAR(1000) NOT NULL,
    active BOOLEAN NOT NULL
);

CREATE TABLE result_recommendations (
    analysis_result_id CHAR(36) NOT NULL,
    recommendation_code VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL,
    PRIMARY KEY (analysis_result_id, recommendation_code),
    CONSTRAINT fk_result_recommendation_result FOREIGN KEY (analysis_result_id) REFERENCES analysis_results(id) ON DELETE CASCADE,
    CONSTRAINT fk_result_recommendation_guide FOREIGN KEY (recommendation_code) REFERENCES recommendations(code)
);
