package com.smartinsole.analysis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.analysis.domain.AnalysisPattern;
import com.smartinsole.analysis.domain.AnalysisResult;
import com.smartinsole.analysis.domain.RuleBasedAnalyzer;
import com.smartinsole.analysis.dto.AnalysisDtos.ComputedAnalysis;
import com.smartinsole.analysis.dto.AnalysisDtos.ComputedPattern;
import com.smartinsole.analysis.repository.AnalysisPatternRepository;
import com.smartinsole.analysis.repository.AnalysisResultRepository;
import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.config.AnalysisProperties;
import com.smartinsole.measurement.repository.MeasurementQualityRepository;
import com.smartinsole.measurement.domain.MeasurementSession;
import com.smartinsole.measurement.repository.MeasurementSessionRepository;
import com.smartinsole.measurement.repository.PressureFrameRepository;
import com.smartinsole.recommendation.domain.Recommendation;
import com.smartinsole.recommendation.repository.RecommendationRepository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisPersistenceService {
    private final MeasurementSessionRepository sessions;
    private final MeasurementQualityRepository qualities;
    private final PressureFrameRepository frames;
    private final AnalysisResultRepository results;
    private final AnalysisPatternRepository patterns;
    private final RecommendationRepository recommendations;
    private final RuleBasedAnalyzer analyzer;
    private final AnalysisProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public AnalysisPersistenceService(MeasurementSessionRepository sessions,
                                      MeasurementQualityRepository qualities,
                                      PressureFrameRepository frames,
                                      AnalysisResultRepository results,
                                      AnalysisPatternRepository patterns,
                                      RecommendationRepository recommendations,
                                      RuleBasedAnalyzer analyzer,
                                      AnalysisProperties properties,
                                      ObjectMapper objectMapper,
                                      JdbcTemplate jdbcTemplate,
                                      Clock clock) {
        this.sessions = sessions;
        this.qualities = qualities;
        this.frames = frames;
        this.results = results;
        this.patterns = patterns;
        this.recommendations = recommendations;
        this.analyzer = analyzer;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional
    public void analyzeAndStore(UUID sessionId, String algorithmVersion) {
        if (!properties.algorithmVersion().equals(algorithmVersion)) {
            throw new IllegalArgumentException("Analyzer implementation is unavailable for " + algorithmVersion);
        }
        MeasurementSession session = sessions.findById(sessionId).orElseThrow();
        if (results.findBySessionIdAndAlgorithmVersion(sessionId, algorithmVersion).isPresent()) {
            if (session.getStatus() == MeasurementStatus.PROCESSING) {
                AnalysisResult existing = results.findBySessionIdAndAlgorithmVersion(sessionId, algorithmVersion)
                        .orElseThrow();
                session.analysisCompleted(existing.getQualityScore(), Instant.now(clock));
            }
            return;
        }
        ComputedAnalysis computed = analyzer.analyze(session, frames.findBySessionOrdered(sessionId),
                qualities.findById(sessionId).orElse(null));
        Instant now = Instant.now(clock);
        AnalysisResult result = new AnalysisResult(sessionId, algorithmVersion, computed.qualityScore(),
                computed.qualityLevel(), computed.missingFrameRate(), computed.cadence(),
                computed.leftContactTimeMs(), computed.rightContactTimeMs(), computed.symmetryIndex(),
                computed.validStepCount(), json(computed.distribution()), json(computed.qualityFlags()),
                json(computed.observationSummary()),
                new AnalysisResult.GaitMetrics(computed.distribution().leftLoadSharePct(),
                        computed.distribution().rightLoadSharePct(), computed.leftStrideTimeMs(),
                        computed.rightStrideTimeMs(), computed.meanStrideTimeMs()),
                computed.movementSummary() == null ? null : json(computed.movementSummary()), now);
        results.saveAndFlush(result);
        int order = 0;
        // Only PARTIALLY/REPEATEDLY observed patterns are stored; NOT_OBSERVED codes live in the summary.
        for (ComputedPattern pattern : computed.patterns()) {
            patterns.save(new AnalysisPattern(result.getId(), pattern.code(), pattern.severity(), pattern.title(),
                    pattern.message(), pattern.evidence(), order++, pattern.observationLevel(),
                    pattern.occurrenceRate(), pattern.observedCount(), pattern.windowCount()));
        }
        linkRecommendations(result.getId(), computed.recommendationCodes());
        session.analysisCompleted(computed.qualityScore(), now);
    }

    private void linkRecommendations(UUID resultId, List<String> requestedCodes) {
        if (requestedCodes.isEmpty()) return;
        Map<String, Recommendation> active = new HashMap<>();
        recommendations.findAllByCodeInAndActiveTrue(requestedCodes)
                .forEach(recommendation -> active.put(recommendation.getCode(), recommendation));
        List<String> codes = requestedCodes.stream().filter(active::containsKey).toList();
        if (codes.isEmpty()) return;
        jdbcTemplate.batchUpdate("""
                        INSERT INTO result_recommendations(analysis_result_id, recommendation_code, sort_order)
                        VALUES (?, ?, ?)
                        """, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        statement.setString(1, resultId.toString());
                        statement.setString(2, codes.get(index));
                        statement.setInt(3, index);
                    }

                    @Override
                    public int getBatchSize() {
                        return codes.size();
                    }
                });
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize deterministic analysis output", exception);
        }
    }
}
