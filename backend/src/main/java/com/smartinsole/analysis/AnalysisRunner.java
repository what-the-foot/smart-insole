package com.smartinsole.analysis;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AnalysisRunner {
    private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);
    private final AnalysisJobStateService states;
    private final AnalysisPersistenceService persistence;

    public AnalysisRunner(AnalysisJobStateService states, AnalysisPersistenceService persistence) {
        this.states = states;
        this.persistence = persistence;
    }

    public void run(UUID sessionId, String algorithmVersion) {
        while (states.markRunning(sessionId, algorithmVersion)) {
            try {
                persistence.analyzeAndStore(sessionId, algorithmVersion);
                states.markCompleted(sessionId, algorithmVersion);
                return;
            } catch (RuntimeException failure) {
                boolean retry = states.recordFailure(sessionId, algorithmVersion, failure);
                if (!retry) {
                    log.error("Analysis failed permanently for session {} and version {}",
                            sessionId, algorithmVersion, failure);
                    return;
                }
                log.warn("Analysis attempt failed; retrying session {} and version {}",
                        sessionId, algorithmVersion, failure);
            }
        }
    }
}
