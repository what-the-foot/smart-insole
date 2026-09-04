package com.smartinsole.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rule-v1.2.0 pattern vocabulary: exactly six neutral observation codes, their display copy and the
 * exercise guide each one maps to. LOW_DATA_QUALITY is not a pattern any more; it lives in
 * dataQuality.flags and triggers REMEASURE_GUIDE through the quality score.
 */
public final class PatternCatalog {
    public static final String MEDIAL_LOAD_TENDENCY = "MEDIAL_LOAD_TENDENCY";
    public static final String LATERAL_LOAD_TENDENCY = "LATERAL_LOAD_TENDENCY";
    public static final String LEFT_RIGHT_ASYMMETRY = "LEFT_RIGHT_ASYMMETRY";
    public static final String LOW_HALLUX_SIGNAL = "LOW_HALLUX_SIGNAL";
    public static final String FOREFOOT_LOAD_TENDENCY = "FOREFOOT_LOAD_TENDENCY";
    public static final String REARFOOT_LOAD_TENDENCY = "REARFOOT_LOAD_TENDENCY";

    public static final String ANKLE_STABILITY_BASIC = "ANKLE_STABILITY_BASIC";
    public static final String BALANCED_FOOT_LOADING = "BALANCED_FOOT_LOADING";
    public static final String REMEASURE_GUIDE = "REMEASURE_GUIDE";

    /** Canonical order used by observationSummary and by tie-breaking of patterns. */
    public static final List<String> CODES = List.of(MEDIAL_LOAD_TENDENCY, LATERAL_LOAD_TENDENCY,
            LEFT_RIGHT_ASYMMETRY, LOW_HALLUX_SIGNAL, FOREFOOT_LOAD_TENDENCY, REARFOOT_LOAD_TENDENCY);

    public record Definition(String code, String title, String message, String windowNoun,
                             String recommendationCode) { }

    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();

    static {
        define(MEDIAL_LOAD_TENDENCY, "내측 압력 집중 경향",
                "발 안쪽 압력이 상대적으로 집중되는 경향이 관찰되었습니다.", "유효 걸음", BALANCED_FOOT_LOADING);
        define(LATERAL_LOAD_TENDENCY, "외측 압력 집중 경향",
                "발 바깥쪽 압력이 상대적으로 집중되는 경향이 관찰되었습니다.", "유효 걸음", BALANCED_FOOT_LOADING);
        define(LEFT_RIGHT_ASYMMETRY, "좌우 접촉 시간 차이",
                "왼발과 오른발의 접촉 시간 차이가 관찰되었습니다.", "좌우 걸음 쌍", ANKLE_STABILITY_BASIC);
        define(LOW_HALLUX_SIGNAL, "엄지발가락 신호 낮음",
                "엄지발가락 센서의 신호 비중이 상대적으로 낮게 관찰되었습니다.", "유효 걸음", BALANCED_FOOT_LOADING);
        define(FOREFOOT_LOAD_TENDENCY, "전족부 압력 집중 경향",
                "발 앞쪽 압력이 상대적으로 집중되는 경향이 관찰되었습니다.", "유효 걸음", BALANCED_FOOT_LOADING);
        define(REARFOOT_LOAD_TENDENCY, "후족부 압력 집중 경향",
                "뒤꿈치 압력이 상대적으로 집중되는 경향이 관찰되었습니다.", "유효 걸음", BALANCED_FOOT_LOADING);
    }

    private PatternCatalog() {
    }

    private static void define(String code, String title, String message, String windowNoun,
                               String recommendationCode) {
        DEFINITIONS.put(code, new Definition(code, title, message, windowNoun, recommendationCode));
    }

    public static Definition definition(String code) {
        Definition definition = DEFINITIONS.get(code);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown pattern code " + code);
        }
        return definition;
    }

    /** Pattern codes that map to the given exercise guide, in canonical order; empty for REMEASURE_GUIDE. */
    public static List<String> patternsFor(String recommendationCode) {
        return CODES.stream()
                .filter(code -> DEFINITIONS.get(code).recommendationCode().equals(recommendationCode))
                .toList();
    }
}
