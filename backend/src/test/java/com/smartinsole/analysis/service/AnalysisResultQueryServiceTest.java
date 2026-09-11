package com.smartinsole.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.analysis.domain.AnalysisPattern;
import com.smartinsole.analysis.domain.AnalysisResult;
import com.smartinsole.analysis.dto.AnalysisDtos.AnalysisResultResponse;
import com.smartinsole.analysis.repository.AnalysisPatternRepository;
import com.smartinsole.analysis.repository.AnalysisResultRepository;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import com.smartinsole.measurement.domain.MeasurementSession;
import com.smartinsole.measurement.repository.MeasurementSessionRepository;
import com.smartinsole.recommendation.repository.RecommendationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.smartinsole.support.TestSessions;
import com.smartinsole.global.common.DomainTypes.ObservationLevel;

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
        assertThat(body.pressureDistribution().leftLoadSharePct()).isNull();
        assertThat(body.pressureDistribution().rightLoadSharePct()).isNull();
        assertThat(body.gaitSummary().leftStrideTimeMs()).isNull();
        assertThat(body.gaitSummary().rightStrideTimeMs()).isNull();
        assertThat(body.gaitSummary().meanStrideTimeMs()).isNull();
        assertThat(body.movementSummary()).isNull();
    }

    /**
     * The service runs at rule-v1.4.0 but a session analysed earlier keeps its rule-v1.2.0 result: the
     * stored version is returned unchanged and every rule-v1.3.0/rule-v1.4.0 field is null, never re-derived.
     */
    @Test
    void exposesObservationFieldsForRuleV120ResultsAndLeavesGaitMetricsNull() {
        Instant now = Instant.parse("2026-09-04T07:10:00Z");
        UUID userId = UUID.randomUUID();
        MeasurementSession session = completedSession(userId, now);
        AnalysisResult current = new AnalysisResult(session.getId(), "rule-v1.2.0", 95, QualityLevel.GOOD,
                0, 100, 500, 500, 0, 21,
                """
                        {"leftMedialRatio":0.5,"leftLateralRatio":0.5,"rightMedialRatio":0.5,"rightLateralRatio":0.5,
                         "leftHeelRatio":0.2,"rightHeelRatio":0.2,"leftMidfootRatio":0.2,"rightMidfootRatio":0.2,
                         "leftForefootRatio":0.6,"rightForefootRatio":0.6,"leftPeakPressure":80,"rightPeakPressure":80,
                         "leftMeanCoP":{"x":0.5,"y":0.5},"rightMeanCoP":{"x":0.5,"y":0.5},
                         "leftSensorSharePct":[10,10,10,10,15,15,15,15],"rightSensorSharePct":[10,10,10,10,15,15,15,15]}
                        """, "[]",
                """
                        [{"code":"MEDIAL_LOAD_TENDENCY","observationLevel":"NOT_OBSERVED","occurrenceRate":0.0,
                          "observedCount":0,"windowCount":21},
                         {"code":"LEFT_RIGHT_ASYMMETRY","observationLevel":"REPEATEDLY_OBSERVED","occurrenceRate":0.62,
                          "observedCount":13,"windowCount":21}]
                        """, now);
        AnalysisPattern pattern = new AnalysisPattern(current.getId(), "LEFT_RIGHT_ASYMMETRY",
                com.smartinsole.global.common.DomainTypes.PatternSeverity.CAUTION, "t", "m", "e", 0,
                ObservationLevel.REPEATEDLY_OBSERVED, 0.62, 13, 21);
        MeasurementSessionRepository sessions = mock(MeasurementSessionRepository.class);
        AnalysisResultRepository results = mock(AnalysisResultRepository.class);
        AnalysisPatternRepository patterns = mock(AnalysisPatternRepository.class);
        when(sessions.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        when(results.findFirstBySessionIdOrderByCreatedAtDescIdDesc(session.getId())).thenReturn(Optional.of(current));
        when(patterns.findAllByAnalysisResultIdOrderBySortOrder(current.getId())).thenReturn(List.of(pattern));
        AnalysisResultQueryService service = new AnalysisResultQueryService(sessions, results, patterns,
                mock(RecommendationRepository.class), mock(JdbcTemplate.class), new ObjectMapper());

        AnalysisResultResponse body = (AnalysisResultResponse) service.result(session.getId(), userId).getBody();

        assertThat(body).isNotNull();
        assertThat(body.observationSummary()).hasSize(2);
        assertThat(body.observationSummary().get(1).observationLevel()).isEqualTo(ObservationLevel.REPEATEDLY_OBSERVED);
        assertThat(body.patterns()).singleElement().satisfies(result -> {
            assertThat(result.observationLevel()).isEqualTo(ObservationLevel.REPEATEDLY_OBSERVED);
            assertThat(result.occurrenceRate()).isEqualTo(0.62);
            assertThat(result.observedCount()).isEqualTo(13);
            assertThat(result.windowCount()).isEqualTo(21);
        });
        assertThat(body.pressureDistribution().leftSensorSharePct()).hasSize(8);
        assertThat(body.gaitSummary().validStepCount()).isEqualTo(21);
        assertThat(body.algorithmVersion()).isEqualTo("rule-v1.2.0");
        assertThat(body.pressureDistribution().leftLoadSharePct()).isNull();
        assertThat(body.pressureDistribution().rightLoadSharePct()).isNull();
        assertThat(body.gaitSummary().leftStrideTimeMs()).isNull();
        assertThat(body.gaitSummary().rightStrideTimeMs()).isNull();
        assertThat(body.gaitSummary().meanStrideTimeMs()).isNull();
        assertThat(body.movementSummary()).isNull();
    }

    @Test
    void exposesLoadShareFromTheDistributionJsonAndStrideTimesFromTheColumnsForRuleV130Results() {
        Instant now = Instant.parse("2026-09-10T07:10:00Z");
        UUID userId = UUID.randomUUID();
        MeasurementSession session = completedSession(userId, now);
        AnalysisResult current = new AnalysisResult(session.getId(), "rule-v1.3.0", 95, QualityLevel.GOOD,
                0, 100, 500, 500, 0, 10,
                """
                        {"leftMedialRatio":0.5,"leftLateralRatio":0.5,"rightMedialRatio":0.5,"rightLateralRatio":0.5,
                         "leftHeelRatio":0.2,"rightHeelRatio":0.2,"leftMidfootRatio":0.2,"rightMidfootRatio":0.2,
                         "leftForefootRatio":0.6,"rightForefootRatio":0.6,"leftPeakPressure":80,"rightPeakPressure":80,
                         "leftMeanCoP":{"x":0.5,"y":0.5},"rightMeanCoP":{"x":0.5,"y":0.5},
                         "leftSensorSharePct":[10,10,10,10,15,15,15,15],"rightSensorSharePct":[10,10,10,10,15,15,15,15],
                         "leftLoadSharePct":55.0,"rightLoadSharePct":45.0}
                        """, "[]", "[]",
                new AnalysisResult.GaitMetrics(55.0, 45.0, 1100.0, null, 1100.0), now);
        MeasurementSessionRepository sessions = mock(MeasurementSessionRepository.class);
        AnalysisResultRepository results = mock(AnalysisResultRepository.class);
        AnalysisPatternRepository patterns = mock(AnalysisPatternRepository.class);
        when(sessions.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        when(results.findFirstBySessionIdOrderByCreatedAtDescIdDesc(session.getId())).thenReturn(Optional.of(current));
        when(patterns.findAllByAnalysisResultIdOrderBySortOrder(current.getId())).thenReturn(List.of());
        AnalysisResultQueryService service = new AnalysisResultQueryService(sessions, results, patterns,
                mock(RecommendationRepository.class), mock(JdbcTemplate.class), new ObjectMapper());

        AnalysisResultResponse body = (AnalysisResultResponse) service.result(session.getId(), userId).getBody();

        assertThat(body).isNotNull();
        assertThat(body.algorithmVersion()).isEqualTo("rule-v1.3.0");
        assertThat(body.pressureDistribution().leftLoadSharePct()).isEqualTo(55.0);
        assertThat(body.pressureDistribution().rightLoadSharePct()).isEqualTo(45.0);
        assertThat(body.gaitSummary().leftStrideTimeMs()).isEqualTo(1100.0);
        assertThat(body.gaitSummary().rightStrideTimeMs()).isNull();
        assertThat(body.gaitSummary().meanStrideTimeMs()).isEqualTo(1100.0);
        // rule-v1.3.0 rows have no V9 column value: the movement summary is null, never re-derived.
        assertThat(body.movementSummary()).isNull();
    }

    @Test
    void exposesTheStoredMovementSummaryJsonForRuleV140ResultsIncludingNullFeet() {
        Instant now = Instant.parse("2026-09-11T07:10:00Z");
        UUID userId = UUID.randomUUID();
        MeasurementSession session = completedSession(userId, now);
        AnalysisResult current = new AnalysisResult(session.getId(), "rule-v1.4.0", 95, QualityLevel.GOOD,
                0, 100, 500, 500, 0, 10,
                """
                        {"leftMedialRatio":0.5,"leftLateralRatio":0.5,"rightMedialRatio":0.5,"rightLateralRatio":0.5,
                         "leftHeelRatio":0.2,"rightHeelRatio":0.2,"leftMidfootRatio":0.2,"rightMidfootRatio":0.2,
                         "leftForefootRatio":0.6,"rightForefootRatio":0.6,"leftPeakPressure":80,"rightPeakPressure":80,
                         "leftMeanCoP":{"x":0.5,"y":0.5},"rightMeanCoP":{"x":0.5,"y":0.5},
                         "leftSensorSharePct":[10,10,10,10,15,15,15,15],"rightSensorSharePct":[10,10,10,10,15,15,15,15],
                         "leftLoadSharePct":55.0,"rightLoadSharePct":45.0}
                        """, "[]", "[]",
                new AnalysisResult.GaitMetrics(55.0, 45.0, 1100.0, null, 1100.0),
                """
                        {"imuCoverage":0.98,"referenceMethod":"QUIET_STANDING",
                         "left":{"frontalTiltDeg":-2.4,"sagittalRangeDeg":38.5,"transverseRangeDeg":9.1,
                                 "swingPeakAngularVelocityDps":312.0,"windowCount":19},
                         "right":null}
                        """, now);
        MeasurementSessionRepository sessions = mock(MeasurementSessionRepository.class);
        AnalysisResultRepository results = mock(AnalysisResultRepository.class);
        AnalysisPatternRepository patterns = mock(AnalysisPatternRepository.class);
        when(sessions.findByIdAndUserId(session.getId(), userId)).thenReturn(Optional.of(session));
        when(results.findFirstBySessionIdOrderByCreatedAtDescIdDesc(session.getId())).thenReturn(Optional.of(current));
        when(patterns.findAllByAnalysisResultIdOrderBySortOrder(current.getId())).thenReturn(List.of());
        AnalysisResultQueryService service = new AnalysisResultQueryService(sessions, results, patterns,
                mock(RecommendationRepository.class), mock(JdbcTemplate.class), new ObjectMapper());

        AnalysisResultResponse body = (AnalysisResultResponse) service.result(session.getId(), userId).getBody();

        assertThat(body).isNotNull();
        assertThat(body.algorithmVersion()).isEqualTo("rule-v1.4.0");
        assertThat(body.movementSummary()).isNotNull();
        assertThat(body.movementSummary().imuCoverage()).isEqualTo(0.98);
        assertThat(body.movementSummary().referenceMethod())
                .isEqualTo(com.smartinsole.analysis.dto.AnalysisDtos.MovementReferenceMethod.QUIET_STANDING);
        assertThat(body.movementSummary().left().frontalTiltDeg()).isEqualTo(-2.4);
        assertThat(body.movementSummary().left().windowCount()).isEqualTo(19);
        assertThat(body.movementSummary().right()).isNull();
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
