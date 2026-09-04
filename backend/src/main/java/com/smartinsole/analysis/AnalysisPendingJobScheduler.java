package com.smartinsole.analysis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.analysis.periodic-dispatch-enabled", matchIfMissing = true)
public class AnalysisPendingJobScheduler {
    private final AnalysisCoordinator coordinator;

    public AnalysisPendingJobScheduler(AnalysisCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${app.analysis.dispatch-interval-ms:1000}")
    public void dispatchPending() {
        coordinator.dispatchPending();
    }
}
