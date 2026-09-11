package com.smartinsole.analysis.service;

import com.smartinsole.analysis.service.AnalysisJobStateService.JobKey;
import com.smartinsole.global.config.AnalysisProperties;
import com.smartinsole.measurement.dto.MeasurementDtos.SessionCompletedEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AnalysisCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AnalysisCoordinator.class);
    private final AnalysisRunner runner;
    private final AnalysisJobStateService states;
    private final Executor executor;
    private final AnalysisProperties properties;
    private final Set<JobKey> inFlight = ConcurrentHashMap.newKeySet();

    public AnalysisCoordinator(AnalysisRunner runner, AnalysisJobStateService states,
                               @Qualifier("analysisExecutor") Executor executor,
                               AnalysisProperties properties) {
        this.runner = runner;
        this.states = states;
        this.executor = executor;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sessionCompleted(SessionCompletedEvent event) {
        submit(new JobKey(event.sessionId(), properties.algorithmVersion()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        states.recoverableJobsOnStartup().forEach(this::submit);
    }

    public void dispatchPending() {
        states.pendingJobs().forEach(this::submit);
    }

    private void submit(JobKey job) {
        if (!inFlight.add(job)) return;
        try {
            executor.execute(() -> {
                try {
                    runner.run(job.sessionId(), job.algorithmVersion());
                } finally {
                    inFlight.remove(job);
                }
            });
        } catch (RuntimeException rejection) {
            inFlight.remove(job);
            // The durable job stays PENDING and the periodic dispatcher retries it without
            // blocking the completion HTTP thread or requiring an application restart.
            log.warn("Could not dispatch durable analysis job for session {} and version {}",
                    job.sessionId(), job.algorithmVersion(), rejection);
        }
    }
}
