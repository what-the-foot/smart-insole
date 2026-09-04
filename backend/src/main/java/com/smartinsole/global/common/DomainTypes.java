package com.smartinsole.global.common;

public final class DomainTypes {
    private DomainTypes() {
    }

    public enum FootSide { LEFT, RIGHT }
    public enum MeasurementStatus { CREATED, MEASURING, PROCESSING, COMPLETED, CANCELLED, FAILED }
    public enum SourceType { DEVICE, SIMULATED }
    public enum DeviceStatus { ACTIVE, INACTIVE, DISCONNECTED, CALIBRATION_REQUIRED }
    public enum QualityLevel { GOOD, ACCEPTABLE, POOR }
    public enum PatternSeverity { INFO, CAUTION, RECHECK }
    public enum ContactState { NO_CONTACT, CONTACT, UNKNOWN }
    public enum AnalysisJobStatus { PENDING, RUNNING, COMPLETED, FAILED }
    public enum SensorRegion { HEEL, MIDFOOT, FOREFOOT, TOE }
    public enum MedialLateral { MEDIAL, CENTER, LATERAL }
    /** Frame Batch 1.1 sensor value processing mode; the MVP BLE wire is RAW only. */
    public enum DataMode { RAW, FILTERED }
    /** Receiver upload state reported through /receiver-status. */
    public enum ReceiverUploadState { STREAMING, UPLOADING, UPLOAD_COMPLETE }
    /**
     * rule-v1.2.0 observation level of a pattern over the valid-step windows of a session. Ordered so
     * that the ordinal reflects strength; it never asserts a diagnosis.
     */
    public enum ObservationLevel { NOT_OBSERVED, PARTIALLY_OBSERVED, REPEATEDLY_OBSERVED }
}
