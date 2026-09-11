package com.smartinsole.analysis.service;

import com.smartinsole.analysis.domain.AnalysisJob;
import com.smartinsole.analysis.repository.AnalysisJobRepository;
import com.smartinsole.global.common.DomainTypes.AnalysisJobStatus;
import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.config.AnalysisProperties;
import com.smartinsole.measurement.domain.MeasurementSession;
import com.smartinsole.measurement.repository.MeasurementSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisJobStateService {
    private final AnalysisJobRepository jobs;
    private final MeasurementSessionRepository sessions;
    private final Clock clock;
    private final AnalysisProperties properties;

    public AnalysisJobStateService(AnalysisJobRepository jobs, MeasurementSessionRepository sessions, Clock clock,
                                   AnalysisProperties properties) {
        this.jobs = jobs;
        this.sessions = sessions;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRunning(UUID sessionId, String algorithmVersion) {
        AnalysisJob job = jobs.findForUpdate(sessionId, algorithmVersion).orElseThrow();
        if (job.getStatus() == AnalysisJobStatus.COMPLETED || job.getStatus() == AnalysisJobStatus.RUNNING
                || job.getStatus() == AnalysisJobStatus.FAILED) {
            return false;
        }
        if (job.getAttemptCount() >= properties.maxAttempts()) {
            Instant now = Instant.now(clock);
            job.fail("ANALYSIS_ATTEMPTS_EXHAUSTED", "Maximum analysis attempts reached", now);
            failProcessingSession(sessionId, now);
            return false;
        }
        job.start(Instant.now(clock));
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID sessionId, String algorithmVersion) {
        AnalysisJob job = jobs.findForUpdate(sessionId, algorithmVersion).orElseThrow();
        job.complete(Instant.now(clock));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(UUID sessionId, String algorithmVersion, Throwable failure) {
        Instant now = Instant.now(clock);
        AnalysisJob job = jobs.findForUpdate(sessionId, algorithmVersion).orElseThrow();
        if (job.getAttemptCount() < properties.maxAttempts()) {
            job.retry("ANALYSIS_ATTEMPT_FAILED", failure.getMessage());
            return true;
        }
        job.fail("ANALYSIS_EXECUTION_FAILED", failure.getMessage(), now);
        failProcessingSession(sessionId, now);
        return false;
    }

    private void failProcessingSession(UUID sessionId, Instant now) {
        sessions.findById(sessionId).ifPresent(session -> {
            if (session.getStatus() == MeasurementStatus.PROCESSING) session.fail(now);
        });
    }

    @Transactional
    public List<JobKey> recoverableJobsOnStartup() {
        List<AnalysisJob> recoverable = jobs.findAllByStatusIn(
                List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING));
        Instant now = Instant.now(clock);
        Set<JobKey> dispatch = new LinkedHashSet<>();
        Set<UUID> sessionsNeedingCurrentVersion = new LinkedHashSet<>();
        for (AnalysisJob job : recoverable) {
            if (!properties.algorithmVersion().equals(job.getAlgorithmVersion())) {
                job.fail("ALGORITHM_VERSION_RETIRED",
                        "The analyzer implementation for this version is unavailable", now);
                sessions.findById(job.getSessionId())
                        .filter(session -> session.getStatus() == MeasurementStatus.PROCESSING)
                        .ifPresent(session -> sessionsNeedingCurrentVersion.add(session.getId()));
                continue;
            }
            if (job.getStatus() == AnalysisJobStatus.RUNNING) {
                job.requeue();
            }
            dispatch.add(new JobKey(job.getSessionId(), job.getAlgorithmVersion()));
        }
        for (UUID sessionId : sessionsNeedingCurrentVersion) {
            AnalysisJob current = jobs.findBySessionIdAndAlgorithmVersion(sessionId, properties.algorithmVersion())
                    .orElseGet(() -> jobs.save(AnalysisJob.pending(sessionId, properties.algorithmVersion(), now)));
            if (current.getStatus() == AnalysisJobStatus.RUNNING) {
                current.requeue();
            }
            if (current.getStatus() == AnalysisJobStatus.PENDING) {
                dispatch.add(new JobKey(sessionId, properties.algorithmVersion()));
            }
        }
        return List.copyOf(dispatch);
    }

    @Transactional(readOnly = true)
    public List<JobKey> pendingJobs() {
        return jobs.findAllByStatusAndAlgorithmVersion(AnalysisJobStatus.PENDING,
                        properties.algorithmVersion()).stream()
                .map(job -> new JobKey(job.getSessionId(), job.getAlgorithmVersion()))
                .toList();
    }

    public record JobKey(UUID sessionId, String algorithmVersion) { }
}
