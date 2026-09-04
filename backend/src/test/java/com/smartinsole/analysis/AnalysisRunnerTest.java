package com.smartinsole.analysis;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalysisRunnerTest {
    @Test
    void retriesOneFailedAttemptAndThenCompletes() {
        UUID sessionId = UUID.randomUUID();
        String version = "rule-retry-test";
        AnalysisJobStateService states = mock(AnalysisJobStateService.class);
        AnalysisPersistenceService persistence = mock(AnalysisPersistenceService.class);
        when(states.markRunning(sessionId, version)).thenReturn(true, true);
        when(states.recordFailure(org.mockito.ArgumentMatchers.eq(sessionId),
                org.mockito.ArgumentMatchers.eq(version), any(RuntimeException.class))).thenReturn(true);
        doThrow(new IllegalStateException("transient database error"))
                .doNothing().when(persistence).analyzeAndStore(sessionId, version);

        new AnalysisRunner(states, persistence).run(sessionId, version);

        verify(states, times(2)).markRunning(sessionId, version);
        verify(persistence, times(2)).analyzeAndStore(sessionId, version);
        verify(states).markCompleted(sessionId, version);
    }
}
