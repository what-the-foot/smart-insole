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
}
