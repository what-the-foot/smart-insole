package com.smartinsole.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartinsole.analysis.service.AnalysisJobStateService.JobKey;
import com.smartinsole.global.config.AnalysisProperties;
import com.smartinsole.measurement.dto.MeasurementDtos.SessionCompletedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import com.smartinsole.support.TestAnalysisProperties;

class AnalysisCoordinatorTest {
    @Test
    void executorRejectionNeverEscapesTheAfterCommitListener() {
        AnalysisRunner runner = mock(AnalysisRunner.class);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("queue is full");
        };
        AnalysisProperties properties = TestAnalysisProperties.defaults();
        AnalysisCoordinator coordinator = new AnalysisCoordinator(runner,
                mock(AnalysisJobStateService.class), rejectingExecutor, properties);

        assertThatCode(() -> coordinator.sessionCompleted(new SessionCompletedEvent(UUID.randomUUID())))
                .doesNotThrowAnyException();
        verifyNoInteractions(runner);
    }

    @Test
    void periodicDispatchRetriesARejectedDurableJobWithoutRestart() {
        JobKey job = new JobKey(UUID.randomUUID(), TestAnalysisProperties.ALGORITHM_VERSION);
        AnalysisRunner runner = mock(AnalysisRunner.class);
        AnalysisJobStateService states = mock(AnalysisJobStateService.class);
        when(states.pendingJobs()).thenReturn(List.of(job));
        Executor rejectOnce = new Executor() {
            private boolean rejected;

            @Override
            public void execute(Runnable command) {
                if (!rejected) {
                    rejected = true;
                    throw new RejectedExecutionException("queue is full");
                }
                command.run();
            }
        };
        AnalysisCoordinator coordinator = new AnalysisCoordinator(runner, states, rejectOnce, properties());

        coordinator.sessionCompleted(new SessionCompletedEvent(job.sessionId()));
        verifyNoInteractions(runner);
        coordinator.dispatchPending();

        verify(runner).run(job.sessionId(), job.algorithmVersion());
    }

    @Test
    void doesNotQueueTheSamePendingJobWhileItIsAlreadyInFlight() {
        JobKey job = new JobKey(UUID.randomUUID(), TestAnalysisProperties.ALGORITHM_VERSION);
        AnalysisRunner runner = mock(AnalysisRunner.class);
        AnalysisJobStateService states = mock(AnalysisJobStateService.class);
        when(states.pendingJobs()).thenReturn(List.of(job));
        List<Runnable> queued = new ArrayList<>();
        AnalysisCoordinator coordinator = new AnalysisCoordinator(runner, states, queued::add, properties());

        coordinator.sessionCompleted(new SessionCompletedEvent(job.sessionId()));
        coordinator.dispatchPending();
        coordinator.dispatchPending();
        assertThat(queued).hasSize(1);

        queued.getFirst().run();
        coordinator.dispatchPending();
        assertThat(queued).hasSize(2);
    }

    @Test
    void startupRecoveryAndPeriodicPendingScanUseSeparateStateQueries() {
        AnalysisRunner runner = mock(AnalysisRunner.class);
        AnalysisJobStateService states = mock(AnalysisJobStateService.class);
        JobKey startup = new JobKey(UUID.randomUUID(), TestAnalysisProperties.ALGORITHM_VERSION);
        JobKey pending = new JobKey(UUID.randomUUID(), TestAnalysisProperties.ALGORITHM_VERSION);
        when(states.recoverableJobsOnStartup()).thenReturn(List.of(startup));
        when(states.pendingJobs()).thenReturn(List.of(pending));
        AnalysisCoordinator coordinator = new AnalysisCoordinator(runner, states, Runnable::run, properties());

        coordinator.recover();
        coordinator.dispatchPending();

        verify(states).recoverableJobsOnStartup();
        verify(states).pendingJobs();
        verify(runner).run(startup.sessionId(), startup.algorithmVersion());
        verify(runner).run(pending.sessionId(), pending.algorithmVersion());
    }

    private static AnalysisProperties properties() {
        return TestAnalysisProperties.defaults();
    }
}
