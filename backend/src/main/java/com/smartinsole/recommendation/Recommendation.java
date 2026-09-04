package com.smartinsole.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "recommendations")
public class Recommendation {
    @Id
    @Column(length = 100)
    private String code;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, length = 1000)
    private String summary;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "instructions_json", nullable = false, columnDefinition = "json")
    private String instructionsJson;
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;
    @Column(name = "caution_text", nullable = false, length = 1000)
    private String cautionText;
    @Column(nullable = false)
    private boolean active;

    protected Recommendation() {
    }

    public Recommendation(String code, String title, String summary, String instructionsJson,
                          int durationMinutes, String cautionText, boolean active) {
        this.code = code;
        this.title = title;
        this.summary = summary;
        this.instructionsJson = instructionsJson;
        this.durationMinutes = durationMinutes;
        this.cautionText = cautionText;
        this.active = active;
    }

    public String getCode() { return code; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public String getInstructionsJson() { return instructionsJson; }
    public int getDurationMinutes() { return durationMinutes; }
    public String getCautionText() { return cautionText; }
    public boolean isActive() { return active; }
}
