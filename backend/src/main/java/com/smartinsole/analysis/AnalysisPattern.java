package com.smartinsole.analysis;

import com.smartinsole.global.common.DomainTypes.PatternSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "analysis_patterns")
public class AnalysisPattern {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "analysis_result_id", nullable = false, length = 36)
    private UUID analysisResultId;

    @Column(name = "pattern_code", nullable = false, length = 100)
    private String patternCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PatternSeverity severity;

    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, length = 1000)
    private String message;
    @Column(nullable = false, length = 1000)
    private String evidence;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected AnalysisPattern() {
    }

    public AnalysisPattern(UUID resultId, String code, PatternSeverity severity, String title,
                           String message, String evidence, int sortOrder) {
        this.analysisResultId = resultId;
        this.patternCode = code;
        this.severity = severity;
        this.title = title;
        this.message = message;
        this.evidence = evidence;
        this.sortOrder = sortOrder;
    }

    public String getPatternCode() { return patternCode; }
    public PatternSeverity getSeverity() { return severity; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public String getEvidence() { return evidence; }
}
