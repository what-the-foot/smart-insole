package com.smartinsole.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.analysis.AnalysisDtos.AnalysisResultResponse;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import com.smartinsole.measurement.MeasurementSession;
import com.smartinsole.measurement.MeasurementSessionRepository;
import com.smartinsole.recommendation.RecommendationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.smartinsole.support.TestSessions;

class AnalysisResultQueryServiceTest {
    @Test
    void exposesMetricsUnavailableInLegacyV1ResultsAsNull() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        UUID userId = UUID.randomUUID();
        MeasurementSession session = completedSession(userId, now);
        AnalysisResult legacy = new AnalysisResult(session.getId(), "rule-v1.0.0", 90, QualityLevel.GOOD,
                0, 100, 500, 500, 0, null,
                """
                        {"leftMedialRatio":0.5,"leftLateralRatio":0.5,
                         "rightMedialRatio":0.5,"rightLateralRatio":0.5,
                         "leftHeelRatio":0.2,"rightHeelRatio":0.2}
                        """, "[]", now);
        MeasurementSessionRepository sessions = mock(MeasurementSessionRepository.class);
        AnalysisResultRepository results = mock(AnalysisResultRepository.class);
        AnalysisPatternRepository patterns = mock(AnalysisPatternRepository.class);
        when(sessions.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        when(results.findFirstBySessionIdOrderByCreatedAtDescIdDesc(session.getId()))
                .thenReturn(Optional.of(legacy));
        when(patterns.findAllByAnalysisResultIdOrderBySortOrder(legacy.getId())).thenReturn(List.of());
        AnalysisResultQueryService service = new AnalysisResultQueryService(sessions, results, patterns,
                mock(RecommendationRepository.class), mock(JdbcTemplate.class), new ObjectMapper());

        AnalysisResultResponse body = (AnalysisResultResponse) service.result(session.getId(), userId).getBody();

        assertThat(body).isNotNull();
        assertThat(body.gaitSummary().validStepCount()).isNull();
        assertThat(body.pressureDistribution().leftMidfootRatio()).isNull();
        assertThat(body.pressureDistribution().rightForefootRatio()).isNull();
        assertThat(body.pressureDistribution().leftPeakPressure()).isNull();
        assertThat(body.pressureDistribution().leftMeanCoP()).isNull();
        assertThat(body.pressureDistribution().leftSensorSharePct()).isNull();
        assertThat(body.observationSummary()).isNull();
    }

    private static MeasurementSession completedSession(UUID userId, Instant now) {
        MeasurementSession session = TestSessions.create(userId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100, null,
                now.minusSeconds(3));
        session.start(now.minusSeconds(2));
        session.complete(now.minusSeconds(1));
        session.analysisCompleted(90, now);
        return session;
    }
}
