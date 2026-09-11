package com.smartinsole.analysis.domain;

import com.smartinsole.global.common.DomainTypes.AnalysisJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "analysis_jobs")
public class AnalysisJob {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "session_id", nullable = false, length = 36)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AnalysisJobStatus status;

    @Column(name = "algorithm_version", nullable = false, length = 50)
    private String algorithmVersion;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AnalysisJob() {
    }

    public static AnalysisJob pending(UUID sessionId, String algorithmVersion, Instant now) {
        AnalysisJob job = new AnalysisJob();
        job.id = UUID.randomUUID();
        job.sessionId = sessionId;
        job.status = AnalysisJobStatus.PENDING;
        job.algorithmVersion = algorithmVersion;
        job.createdAt = now;
        return job;
    }

    public void start(Instant now) {
        status = AnalysisJobStatus.RUNNING;
        attemptCount++;
        startedAt = now;
        errorCode = null;
        errorMessage = null;
    }

    public void complete(Instant now) {
        status = AnalysisJobStatus.COMPLETED;
        completedAt = now;
    }

    public void fail(String code, String message, Instant now) {
        status = AnalysisJobStatus.FAILED;
        errorCode = code;
        errorMessage = message == null ? null : message.substring(0, Math.min(message.length(), 500));
        completedAt = now;
    }

    public void retry(String code, String message) {
        status = AnalysisJobStatus.PENDING;
        errorCode = code;
        errorMessage = message == null ? null : message.substring(0, Math.min(message.length(), 500));
        startedAt = null;
        completedAt = null;
    }

    public void requeue() {
        status = AnalysisJobStatus.PENDING;
        startedAt = null;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public AnalysisJobStatus getStatus() { return status; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public int getAttemptCount() { return attemptCount; }
}
