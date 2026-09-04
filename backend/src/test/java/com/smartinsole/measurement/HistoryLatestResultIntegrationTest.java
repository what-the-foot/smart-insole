package com.smartinsole.measurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartinsole.analysis.AnalysisPattern;
import com.smartinsole.analysis.AnalysisPatternRepository;
import com.smartinsole.analysis.AnalysisResult;
import com.smartinsole.analysis.AnalysisResultRepository;
import com.smartinsole.global.common.DomainTypes.PatternSeverity;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import com.smartinsole.support.TestSessions;

@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:history_latest;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HistoryLatestResultIntegrationTest {
    @Autowired MeasurementSessionRepository sessions;
    @Autowired AnalysisResultRepository results;
    @Autowired AnalysisPatternRepository patterns;
    @Autowired HistoryProjectionRepository projections;

    @Test
    void filtersAndProjectsPatternsFromOnlyTheLatestResult() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        UUID userId = UUID.randomUUID();
        MeasurementSession session = TestSessions.create(userId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100, null,
                now.minusSeconds(10));
        session.start(now.minusSeconds(9));
        session.complete(now.minusSeconds(8));
        session.analysisCompleted(90, now.minusSeconds(7));
        sessions.saveAndFlush(session);

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
        assertThat(projections.primaryPatterns(List.of(session.getId())))
                .containsEntry(session.getId(), "LATEST_PATTERN");
    }

    private static AnalysisResult result(UUID sessionId, String version, Integer validStepCount,
                                         Instant createdAt) {
        return new AnalysisResult(sessionId, version, 90, QualityLevel.GOOD, 0, 100,
                500, 500, 0, validStepCount,
                """
                        {"leftMedialRatio":0.5,"leftLateralRatio":0.5,
                         "rightMedialRatio":0.5,"rightLateralRatio":0.5,
                         "leftHeelRatio":0.2,"rightHeelRatio":0.2}
                        """, "[]", createdAt);
    }
}
