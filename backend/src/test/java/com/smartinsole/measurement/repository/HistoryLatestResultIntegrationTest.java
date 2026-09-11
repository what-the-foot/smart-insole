package com.smartinsole.measurement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartinsole.analysis.domain.AnalysisPattern;
import com.smartinsole.analysis.domain.AnalysisResult;
import com.smartinsole.analysis.domain.AnalysisResult.GaitMetrics;
import com.smartinsole.analysis.repository.AnalysisPatternRepository;
import com.smartinsole.analysis.repository.AnalysisResultRepository;
import com.smartinsole.global.common.DomainTypes.PatternSeverity;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import com.smartinsole.measurement.domain.MeasurementSession;
import com.smartinsole.measurement.repository.HistoryProjectionRepository.LatestResultSummary;
import com.smartinsole.support.TestSessions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:history_latest;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HistoryLatestResultIntegrationTest {
    private static final String DISTRIBUTION_JSON = """
            {"leftMedialRatio":0.5,"leftLateralRatio":0.5,
             "rightMedialRatio":0.5,"rightLateralRatio":0.5,
             "leftHeelRatio":0.2,"rightHeelRatio":0.2}
            """;

    @Autowired MeasurementSessionRepository sessions;
    @Autowired AnalysisResultRepository results;
    @Autowired AnalysisPatternRepository patterns;
    @Autowired HistoryProjectionRepository projections;

    @Test
    void filtersAndProjectsPatternsFromOnlyTheLatestResult() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        UUID userId = UUID.randomUUID();
        MeasurementSession session = completedSession(userId, now);

        AnalysisResult legacy = results.saveAndFlush(result(session.getId(), "rule-v1.0.0", null,
                now.minusSeconds(2)));
        AnalysisResult latest = results.saveAndFlush(result(session.getId(), "rule-v1.1.0", 2, now));
        patterns.saveAndFlush(new AnalysisPattern(legacy.getId(), "LEGACY_PATTERN", PatternSeverity.INFO,
                "legacy", "legacy", "legacy", 0));
        patterns.saveAndFlush(new AnalysisPattern(latest.getId(), "LATEST_PATTERN", PatternSeverity.INFO,
                "latest", "latest", "latest", 0));

        assertThat(results.findById(legacy.getId()).orElseThrow().getValidStepCount()).isNull();
        assertThat(results.findById(latest.getId()).orElseThrow().getValidStepCount()).isEqualTo(2);
        assertThat(sessions.searchHistory(userId.toString(), null, null, null, null,
                "LEGACY_PATTERN", PageRequest.of(0, 20)).getTotalElements()).isZero();
        assertThat(sessions.searchHistory(userId.toString(), null, null, null, null,
                "LATEST_PATTERN", PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);

        Map<UUID, LatestResultSummary> summaries = projections.latestResults(List.of(session.getId()));
        assertThat(summaries).containsOnlyKeys(session.getId());
        LatestResultSummary summary = summaries.get(session.getId());
        assertThat(summary.primaryPatternCode()).isEqualTo("LATEST_PATTERN");
        // Legacy (pre rule-v1.3.0) result: the base metrics come from the row, the V8 columns stay null.
        assertThat(summary.algorithmVersion()).isEqualTo("rule-v1.1.0");
        assertThat(summary.dataQualityLevel()).isEqualTo(QualityLevel.GOOD);
        assertThat(summary.symmetryIndex()).isEqualTo(0.0);
        assertThat(summary.cadence()).isEqualTo(100.0);
        assertThat(summary.leftContactTimeMs()).isEqualTo(500.0);
        assertThat(summary.rightContactTimeMs()).isEqualTo(500.0);
        assertThat(summary.validStepCount()).isEqualTo(2);
        assertThat(summary.leftLoadSharePct()).isNull();
        assertThat(summary.rightLoadSharePct()).isNull();
        assertThat(summary.meanStrideTimeMs()).isNull();
    }

    @Test
    void projectsTheRuleV130GaitMetricsOfTheLatestResultInOneQuery() {
        Instant now = Instant.parse("2026-09-02T08:10:00Z");
        UUID userId = UUID.randomUUID();
        MeasurementSession withPatterns = completedSession(userId, now);
        MeasurementSession withoutPatterns = completedSession(userId, now.plusSeconds(60));
        MeasurementSession neverAnalysed = TestSessions.create(userId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100, null, now);
        sessions.saveAndFlush(neverAnalysed);

        // An earlier rule-v1.2.0 result must lose to the newer rule-v1.3.0 one, whatever its patterns say.
        AnalysisResult earlier = results.saveAndFlush(result(withPatterns.getId(), "rule-v1.2.0", 4,
                now.minusSeconds(30)));
        patterns.saveAndFlush(new AnalysisPattern(earlier.getId(), "EARLIER_PATTERN", PatternSeverity.INFO,
                "earlier", "earlier", "earlier", 0));
        AnalysisResult latest = results.saveAndFlush(new AnalysisResult(withPatterns.getId(), "rule-v1.3.0",
                72, QualityLevel.ACCEPTABLE, 0.05, 104.5, 612.0, 588.0, 4.2, 6, DISTRIBUTION_JSON, "[]", null,
                new GaitMetrics(48.5, 51.5, 1080.0, 1120.0, 1100.0), now));
        patterns.saveAndFlush(new AnalysisPattern(latest.getId(), "SECOND_PATTERN", PatternSeverity.INFO,
                "second", "second", "second", 1));
        patterns.saveAndFlush(new AnalysisPattern(latest.getId(), "FIRST_PATTERN", PatternSeverity.INFO,
                "first", "first", "first", 0));
        // A rule-v1.3.0 result whose analyzer could not derive the metrics (one foot without contact,
        // fewer than two windows) and that observed no pattern at all.
        results.saveAndFlush(new AnalysisResult(withoutPatterns.getId(), "rule-v1.3.0", 40, QualityLevel.POOR,
                0.3, 0.0, 0.0, 0.0, 0.0, 0, DISTRIBUTION_JSON, "[]", null,
                new GaitMetrics(null, null, null, null, null), now.plusSeconds(90)));

        Map<UUID, LatestResultSummary> summaries = projections.latestResults(
                List.of(withPatterns.getId(), withoutPatterns.getId(), neverAnalysed.getId()));

        assertThat(summaries).containsOnlyKeys(withPatterns.getId(), withoutPatterns.getId());
        assertThat(summaries.get(withPatterns.getId())).isEqualTo(new LatestResultSummary("FIRST_PATTERN",
                "rule-v1.3.0", QualityLevel.ACCEPTABLE, 4.2, 104.5, 612.0, 588.0, 6, 48.5, 51.5, 1100.0));
        assertThat(summaries.get(withoutPatterns.getId())).isEqualTo(new LatestResultSummary(null,
                "rule-v1.3.0", QualityLevel.POOR, 0.0, 0.0, 0.0, 0.0, 0, null, null, null));
        assertThat(projections.latestResults(List.of())).isEmpty();
    }

    private MeasurementSession completedSession(UUID userId, Instant now) {
        MeasurementSession session = TestSessions.create(userId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100, null,
                now.minusSeconds(10));
        session.start(now.minusSeconds(9));
        session.complete(now.minusSeconds(8));
        session.analysisCompleted(90, now.minusSeconds(7));
        return sessions.saveAndFlush(session);
    }

    private static AnalysisResult result(UUID sessionId, String version, Integer validStepCount,
                                         Instant createdAt) {
        return new AnalysisResult(sessionId, version, 90, QualityLevel.GOOD, 0, 100,
                500, 500, 0, validStepCount, DISTRIBUTION_JSON, "[]", createdAt);
    }
}
