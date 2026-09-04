package com.smartinsole.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartinsole.global.common.DomainTypes.AnalysisJobStatus;
import com.smartinsole.measurement.MeasurementSession;
import com.smartinsole.measurement.MeasurementSessionRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnalysisJobStateServiceConcurrencyTest {
    @Autowired AnalysisJobRepository jobs;
    @Autowired AnalysisJobStateService states;
    @Autowired MeasurementSessionRepository sessions;

    @BeforeEach
    void cleanDatabase() {
        jobs.deleteAll();
        sessions.deleteAll();
    }

    @Test
    void onlyOneConcurrentRunnerCanClaimAJob() throws Exception {
        UUID sessionId = UUID.randomUUID();
        String algorithmVersion = "rule-concurrency-test";
        AnalysisJob job = jobs.saveAndFlush(AnalysisJob.pending(sessionId, algorithmVersion, Instant.now()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> claimAfterBarrier(
                    ready, start, sessionId, algorithmVersion));
            Future<Boolean> second = executor.submit(() -> claimAfterBarrier(
                    ready, start, sessionId, algorithmVersion));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(java.util.List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }

        AnalysisJob stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AnalysisJobStatus.RUNNING);
        assertThat(stored.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void keepsTheJobPendingUntilTheConfiguredAttemptLimit() {
        UUID sessionId = UUID.randomUUID();
        String algorithmVersion = "rule-retry-state-test";
        AnalysisJob job = jobs.saveAndFlush(AnalysisJob.pending(sessionId, algorithmVersion, Instant.now()));

        assertThat(states.markRunning(sessionId, algorithmVersion)).isTrue();
        assertThat(states.recordFailure(sessionId, algorithmVersion,
                new IllegalStateException("temporary"))).isTrue();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.PENDING);

        assertThat(states.markRunning(sessionId, algorithmVersion)).isTrue();
        assertThat(states.recordFailure(sessionId, algorithmVersion,
                new IllegalStateException("permanent"))).isFalse();
        AnalysisJob stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(stored.getAttemptCount()).isEqualTo(2);
    }

    @Test
    void replacesAnUnfinishedRetiredVersionWithTheCurrentAlgorithmVersion() {
        Instant now = Instant.now();
        MeasurementSession session = MeasurementSession.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100,
                null, now.minusSeconds(1));
        session.start(now.minusSeconds(1));
        session.complete(now);
        sessions.saveAndFlush(session);
        AnalysisJob retired = jobs.saveAndFlush(AnalysisJob.pending(session.getId(), "rule-v0.9.0", now));

        var recoverable = states.recoverableJobsOnStartup();

        assertThat(jobs.findById(retired.getId()).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(recoverable).containsExactly(
                new AnalysisJobStateService.JobKey(session.getId(), "rule-v1.1.0"));
        assertThat(jobs.findBySessionIdAndAlgorithmVersion(session.getId(), "rule-v1.1.0")
                .orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        assertThat(states.pendingJobs()).containsExactly(
                new AnalysisJobStateService.JobKey(session.getId(), "rule-v1.1.0"));
    }

    private boolean claimAfterBarrier(CountDownLatch ready, CountDownLatch start, UUID sessionId,
                                      String algorithmVersion) throws InterruptedException {
        ready.countDown();
        start.await();
        return states.markRunning(sessionId, algorithmVersion);
    }
}
