package com.smartinsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.device.SensorLayout;
import com.smartinsole.device.SensorLayoutRepository;
import com.smartinsole.recommendation.Recommendation;
import com.smartinsole.recommendation.RecommendationRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:api_flow;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        // The fixture flow yields one contact window per foot; observe patterns from a single window.
        "app.analysis.min-observation-windows=1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SensorLayoutRepository layouts;
    @Autowired RecommendationRepository recommendations;

    @Test
    void completeAuthenticatedFixtureFlowIsIdempotentAndOwned() throws Exception {
        layouts.save(new SensorLayout("layout-v1", 8, points(), true,
                Instant.parse("2026-09-02T07:00:00Z")));
        recommendations.save(new Recommendation("ANKLE_STABILITY_BASIC", "기본 발목 안정화 운동",
                "균형 유지 목적", "[\"한 발로 섭니다.\",\"양쪽 각 3회 반복합니다.\"]", 10,
                "통증이 생기면 중단하세요.", true));

        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"large@example.com\",\"padding\":\""
                                + "a".repeat(1_048_576) + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        mvc.perform(get("/api/v1/auth/signup"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        signup("too-long-ascii@example.com", "Alice", "a".repeat(73))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        signup("too-long-korean@example.com", "Alice", "가".repeat(25))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        signup("alice@example.com", "Alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@example.com"));
        signup("ALICE@example.com", "Alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
        String aliceToken = signin("alice@example.com");

        mvc.perform(get("/api/v1/no-such-resource").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(get("/api/v1/recommendations/ANKLE_STABILITY_BASIC")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("균형 유지 목적"))
                .andExpect(jsonPath("$.instructions.length()").value(2))
                .andExpect(jsonPath("$.relatedPatternCodes[0]").value("LEFT_RIGHT_ASYMMETRY"));

        mvc.perform(get("/api/v1/devices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        String leftId = register(aliceToken, "INSOLE-L-001", "LEFT");
        String rightId = register(aliceToken, "INSOLE-R-001", "RIGHT");
        mvc.perform(get("/api/v1/devices").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activeCalibrationVersion").value("identity-v1"))
                .andExpect(jsonPath("$[1].activeCalibrationVersion").value("identity-v1"));
        String sessionId = createAndStart(aliceToken, leftId, rightId);

        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", "test-receiver-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"padding\":\"" + "a".repeat(1_048_576) + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        String batch = batch(leftId, rightId, 1, true);
        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RECEIVER_UNAUTHORIZED"));

        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", "test-receiver-key")
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(2))
                .andExpect(jsonPath("$.rejectedCount").value(1))
                .andExpect(jsonPath("$.rejections[0].code").value("INVALID_SENSOR_COUNT"));

        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", "test-receiver-key")
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(0))
                .andExpect(jsonPath("$.duplicateCount").value(2));

        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", "test-receiver-key")
                        .contentType(MediaType.APPLICATION_JSON).content(batch(leftId, rightId, 3, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(2));

        mvc.perform(get("/api/v1/measurement-sessions/{id}/realtime-snapshot", sessionId)
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.left.lastSequence").value(3))
                .andExpect(jsonPath("$.right.lastSequence").value(3))
                .andExpect(jsonPath("$.quality.flags[?(@ == 'SEQUENCE_GAP')]").exists());

        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", "test-receiver-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchWithDuplicateFrame(leftId, rightId, 4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(2))
                .andExpect(jsonPath("$.duplicateCount").value(1));

        String leftCursorPath = "$['lastSequenceByDevice']['" + leftId + "']";
        String rightCursorPath = "$['lastSequenceByDevice']['" + rightId + "']";
        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", "test-receiver-key")
                        .contentType(MediaType.APPLICATION_JSON).content(batch(leftId, rightId, 2, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(2))
                .andExpect(jsonPath(leftCursorPath).value(4))
                .andExpect(jsonPath(rightCursorPath).value(4));
        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", "test-receiver-key")
                        .contentType(MediaType.APPLICATION_JSON).content(batch(leftId, rightId, 2, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(0))
                .andExpect(jsonPath("$.duplicateCount").value(2))
                .andExpect(jsonPath(leftCursorPath).value(4))
                .andExpect(jsonPath(rightCursorPath).value(4));

        signup("bob@example.com", "Bob").andExpect(status().isCreated());
        String bobToken = signin("bob@example.com");
        mvc.perform(get("/api/v1/measurement-sessions/{id}", sessionId)
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc.perform(post("/api/v1/measurement-sessions/{id}/complete", sessionId)
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        MvcResult result = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            result = mvc.perform(get("/api/v1/measurement-sessions/{id}/result", sessionId)
                            .header("Authorization", bearer(aliceToken)))
                    .andReturn();
            if (result.getResponse().getStatus() == 200) break;
            Thread.sleep(25);
        }
        assertThat(result).isNotNull();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.path("algorithmVersion").asText()).isEqualTo("rule-v1.2.0");
        assertThat(response.path("gaitSummary").path("validStepCount").asInt()).isEqualTo(2);
        assertThat(response.path("pressureDistribution").path("leftMidfootRatio").isNumber()).isTrue();
        assertThat(response.path("pressureDistribution").path("rightForefootRatio").isNumber()).isTrue();
        assertThat(response.path("pressureDistribution").path("leftPeakPressure").asDouble())
                .isBetween(0.0, 100.0);
        assertThat(response.path("pressureDistribution").path("leftMeanCoP").path("x").isNumber()).isTrue();
        assertThat(response.path("disclaimer").asText()).contains("의료 진단이 아니며");
        String primaryPatternCode = response.path("patterns").path(0).path("code").asText();
        assertThat(primaryPatternCode).isNotBlank();
        JsonNode summary = response.path("observationSummary");
        assertThat(summary.isArray()).isTrue();
        assertThat(summary.size()).isEqualTo(6);
        java.util.List<String> summaryCodes = new java.util.ArrayList<>();
        summary.forEach(item -> summaryCodes.add(item.path("code").asText()));
        assertThat(summaryCodes).containsExactly("MEDIAL_LOAD_TENDENCY", "LATERAL_LOAD_TENDENCY",
                "LEFT_RIGHT_ASYMMETRY", "LOW_HALLUX_SIGNAL", "FOREFOOT_LOAD_TENDENCY", "REARFOOT_LOAD_TENDENCY");
        assertThat(summaryCodes).contains(primaryPatternCode);
        JsonNode primary = response.path("patterns").path(0);
        assertThat(primary.path("observationLevel").asText()).isIn("PARTIALLY_OBSERVED", "REPEATEDLY_OBSERVED");
        assertThat(primary.path("windowCount").asInt()).isEqualTo(2);
        assertThat(primary.path("occurrenceRate").asDouble()).isBetween(0.2, 1.0);
        response.path("patterns").forEach(pattern -> assertThat(pattern.path("code").asText())
                .isNotIn("HIGH_MIDFOOT_LOAD", "SHORT_CONTACT_TIME", "LOW_DATA_QUALITY"));
        assertThat(response.path("pressureDistribution").path("leftSensorSharePct").size()).isEqualTo(8);

        mvc.perform(get("/api/v1/measurement-sessions")
                        .header("Authorization", bearer(aliceToken))
                        .param("from", "2020-01-01T00:00:00Z")
                        .param("to", "2030-01-01T00:00:00Z")
                        .param("minQualityScore", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].primaryPatternCode").isNotEmpty());

        mvc.perform(get("/api/v1/measurement-sessions")
                        .header("Authorization", bearer(aliceToken))
                        .param("patternCode", primaryPatternCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].primaryPatternCode").value(primaryPatternCode));

        mvc.perform(get("/api/v1/measurement-sessions")
                        .header("Authorization", bearer(aliceToken))
                        .param("patternCode", "NO_SUCH_PATTERN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(post("/api/v1/measurement-sessions/{id}/complete", sessionId)
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_STATE"));

        // A batch that arrives after completion is dropped explicitly (no Retry-After).
        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", "test-receiver-key")
                        .contentType(MediaType.APPLICATION_JSON).content(batch(leftId, rightId, 9, false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_MEASURING"))
                .andExpect(jsonPath("$.details.currentStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.details.disposition").value("DROP"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Batch-Disposition", "DROP"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .doesNotExist("Retry-After"));

        mvc.perform(post("/internal/v1/devices/{id}/heartbeat", leftId)
                        .header("X-Receiver-Key", "test-receiver-key").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "receiverId", "GATEWAY-DEV-001", "observedAt", "2026-09-04T01:00:20Z",
                                "connected", true, "batteryPercent", 80.0, "batteryMv", 3900,
                                "firmwareVersion", "0.2.0"))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/devices").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.deviceId == '" + leftId + "')].lastBatteryMv").value(3900))
                .andExpect(jsonPath("$[?(@.deviceId == '" + leftId + "')].firmwareVersion").value("0.2.0"))
                .andExpect(jsonPath("$[?(@.deviceId == '" + leftId + "')].adcMax").value(4095));
    }

    private org.springframework.test.web.servlet.ResultActions signup(String email, String name) throws Exception {
        return signup(email, name, "password123");
    }

    private org.springframework.test.web.servlet.ResultActions signup(String email, String name, String password)
            throws Exception {
        return mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                        "email", email, "password", password, "name", name))));
    }

    private String signin(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/signin").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", email, "password", "password123"))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
    }

    private String register(String token, String serial, String side) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/devices")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "serialNumber", serial, "displayName", serial, "footSide", side,
                                "sensorCount", 8, "sensorLayoutVersion", "layout-v1",
                                "firmwareVersion", "0.1.0"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activeCalibrationVersion").value("identity-v1"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("deviceId").asText();
    }

    private String createAndStart(String token, String left, String right) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/measurement-sessions")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "leftDeviceId", left, "rightDeviceId", right, "sampleRateHz", 100))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("DEVICE"))
                .andReturn();
        String id = objectMapper.readTree(result.getResponse().getContentAsString()).path("sessionId").asText();
        mvc.perform(post("/api/v1/measurement-sessions/{id}/start", id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MEASURING"));
        return id;
    }

    private String batch(String left, String right, long sequence, boolean includeInvalid) throws Exception {
        java.util.List<Object> frames = new java.util.ArrayList<>();
        frames.add(frame(left, "LEFT", sequence, sequence * 10, 8));
        frames.add(frame(right, "RIGHT", sequence, sequence * 10, 8));
        if (includeInvalid) frames.add(frame(right, "RIGHT", sequence + 10, sequence * 10 + 1, 7));
        return objectMapper.writeValueAsString(java.util.Map.of(
                "schemaVersion", "1.0", "receiverId", "RECEIVER-PC-001",
                "sentAt", "2026-09-02T07:11:10.200Z", "frames", frames));
    }

    private String batchWithDuplicateFrame(String left, String right, long sequence) throws Exception {
        Object leftFrame = frame(left, "LEFT", sequence, sequence * 10, 8);
        return objectMapper.writeValueAsString(java.util.Map.of(
                "schemaVersion", "1.0", "receiverId", "RECEIVER-PC-001",
                "sentAt", "2026-09-02T07:11:10.200Z",
                "frames", java.util.List.of(leftFrame, leftFrame,
                        frame(right, "RIGHT", sequence, sequence * 10, 8))));
    }

    private java.util.Map<String, Object> frame(String device, String side, long sequence,
                                                long time, int sensorCount) {
        java.util.List<Integer> values = new java.util.ArrayList<>();
        for (int index = 0; index < sensorCount; index++) values.add(500 + index * 100);
        return java.util.Map.of("deviceId", device, "footSide", side, "sequence", sequence,
                "deviceTimeMs", time, "sensorValues", values);
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private static String points() {
        return """
                [{"index":0,"x":0.50,"y":0.90,"region":"HEEL","medialLateral":"CENTER"},
                 {"index":1,"x":0.36,"y":0.68,"region":"MIDFOOT","medialLateral":"MEDIAL"},
                 {"index":2,"x":0.64,"y":0.68,"region":"MIDFOOT","medialLateral":"LATERAL"},
                 {"index":3,"x":0.30,"y":0.42,"region":"FOREFOOT","medialLateral":"MEDIAL"},
                 {"index":4,"x":0.50,"y":0.40,"region":"FOREFOOT","medialLateral":"CENTER"},
                 {"index":5,"x":0.70,"y":0.42,"region":"FOREFOOT","medialLateral":"LATERAL"},
                 {"index":6,"x":0.40,"y":0.15,"region":"TOE","medialLateral":"MEDIAL"},
                 {"index":7,"x":0.60,"y":0.15,"region":"TOE","medialLateral":"LATERAL"}]
                """;
    }
}
