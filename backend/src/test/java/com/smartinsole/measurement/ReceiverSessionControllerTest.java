package com.smartinsole.measurement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.device.SensorLayout;
import com.smartinsole.device.SensorLayoutRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:receiver_sessions;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReceiverSessionControllerTest {
    private static final String KEY = "test-receiver-key";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SensorLayoutRepository layouts;

    @Test
    void receiverLooksUpSessionsReportsUploadStateAndHeartbeats() throws Exception {
        layouts.save(new SensorLayout("layout-s01s08-v1", 8, points(), true, Instant.parse("2026-09-04T00:00:00Z")));
        String token = signupAndSignin("receiver-owner@example.com");
        String leftId = register(token, "SMART-INSOLE-L-12345678", "LEFT");
        String rightId = register(token, "SMART-INSOLE-R-9ABCDEF0", "RIGHT");
        String sessionId = create(token, leftId, rightId);

        mvc.perform(get("/internal/v1/measurement-sessions/{id}", sessionId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RECEIVER_UNAUTHORIZED"));
        mvc.perform(get("/internal/v1/measurement-sessions/{id}", sessionId).header("X-Receiver-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/measurement-sessions/{id}", "6f0e1a2b-0000-4000-8000-000000000000")
                        .header("X-Receiver-Key", KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // CREATED: visible by id, absent from the MEASURING list, and frame batches are told to RETRY.
        mvc.perform(get("/internal/v1/measurement-sessions/{id}", sessionId).header("X-Receiver-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.sampleRateHz").value(50))
                .andExpect(jsonPath("$.sourceType").value("DEVICE"))
                .andExpect(jsonPath("$.adcMax").value(4095))
                .andExpect(jsonPath("$.startedAt").isEmpty())
                .andExpect(jsonPath("$.endedAt").isEmpty())
                .andExpect(jsonPath("$.left.deviceId").value(leftId))
                .andExpect(jsonPath("$.left.serialNumber").value("SMART-INSOLE-L-12345678"))
                .andExpect(jsonPath("$.left.footSide").value("LEFT"))
                .andExpect(jsonPath("$.left.sensorCount").value(8))
                .andExpect(jsonPath("$.left.sensorLayoutVersion").value("layout-s01s08-v1"))
                .andExpect(jsonPath("$.left.adcMax").value(4095))
                .andExpect(jsonPath("$.left.firmwareVersion").value("0.2.0"))
                .andExpect(jsonPath("$.right.deviceId").value(rightId))
                .andExpect(jsonPath("$.receiverState").isEmpty())
                .andExpect(jsonPath("$.receiverPendingBatches").isEmpty());
        mvc.perform(get("/internal/v1/measurement-sessions").param("status", "MEASURING")
                        .header("X-Receiver-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/internal/v1/measurement-sessions").param("status", "COMPLETED")
                        .header("X-Receiver-Key", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/internal/v1/measurement-sessions").header("X-Receiver-Key", KEY))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(batch(leftId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_MEASURING"))
                .andExpect(jsonPath("$.details.currentStatus").value("CREATED"))
                .andExpect(jsonPath("$.details.disposition").value("RETRY"))
                .andExpect(header().string("X-Batch-Disposition", "RETRY"))
                .andExpect(header().string("Retry-After", "2"));
        mvc.perform(post("/internal/v1/measurement-sessions/{id}/receiver-status", sessionId)
                        .header("X-Receiver-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(receiverStatus("STREAMING", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_MEASURING"));

        // MEASURING: listed (optionally by device serial) and accepting upload status reports.
        mvc.perform(post("/api/v1/measurement-sessions/{id}/start", sessionId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/v1/measurement-sessions").param("status", "MEASURING")
                        .header("X-Receiver-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sessionId").value(sessionId))
                .andExpect(jsonPath("$.items[0].startedAt").isNotEmpty());
        mvc.perform(get("/internal/v1/measurement-sessions").param("status", "MEASURING")
                        .param("deviceSerial", "SMART-INSOLE-R-9ABCDEF0").header("X-Receiver-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sessionId").value(sessionId));
        mvc.perform(get("/internal/v1/measurement-sessions").param("status", "MEASURING")
                        .param("deviceSerial", "SMART-INSOLE-L-00000000").header("X-Receiver-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(post("/internal/v1/measurement-sessions/{id}/receiver-status", sessionId)
                        .header("X-Receiver-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(receiverStatus("UPLOADING", 4)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/internal/v1/measurement-sessions/{id}/receiver-status", sessionId)
                        .header("X-Receiver-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverId\":\"GATEWAY-DEV-001\",\"state\":\"UPLOADING\",\"pendingBatchCount\":-1,"
                                + "\"observedAt\":\"2026-09-04T01:00:10Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/internal/v1/measurement-sessions/{id}", sessionId).header("X-Receiver-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MEASURING"))
                .andExpect(jsonPath("$.receiverState").value("UPLOADING"))
                .andExpect(jsonPath("$.receiverPendingBatches").value(4));
        mvc.perform(get("/api/v1/measurement-sessions/{id}", sessionId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiverState").value("UPLOADING"))
                .andExpect(jsonPath("$.receiverPendingBatches").value(4))
                .andExpect(jsonPath("$.adcMax").value(4095));

        // Heartbeat with the 1.1 battery/firmware fields is reflected on the owner's device list.
        mvc.perform(post("/internal/v1/devices/{id}/heartbeat", leftId)
                        .header("X-Receiver-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "receiverId", "GATEWAY-DEV-001", "observedAt", "2026-09-04T01:00:20Z",
                                "connected", true, "batteryPercent", 80.0, "batteryMv", 3900,
                                "firmwareVersion", "0.2.1", "rssi", -58))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/devices").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.deviceId == '" + leftId + "')].lastBatteryMv").value(3900))
                .andExpect(jsonPath("$[?(@.deviceId == '" + leftId + "')].lastBatteryPercent").value(80.0))
                .andExpect(jsonPath("$[?(@.deviceId == '" + leftId + "')].firmwareVersion").value("0.2.1"))
                .andExpect(jsonPath("$[?(@.deviceId == '" + leftId + "')].adcMax").value(4095));

        // The receiver's pending report survives completion and is reflected in the quality flags.
        mvc.perform(post("/api/v1/measurement-sessions/{id}/complete", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/v1/measurement-sessions/{id}", sessionId).header("X-Receiver-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.in(List.of("PROCESSING", "COMPLETED"))))
                .andExpect(jsonPath("$.endedAt").isNotEmpty())
                .andExpect(jsonPath("$.receiverPendingBatches").value(4));
    }

    private String signupAndSignin(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", "password123", "name", "Owner"))))
                .andExpect(status().isCreated());
        MvcResult result = mvc.perform(post("/api/v1/auth/signin").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", "password123"))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
    }

    private String register(String token, String serial, String side) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "serialNumber", serial, "displayName", serial, "footSide", side,
                                "sensorCount", 8, "sensorLayoutVersion", "layout-s01s08-v1",
                                "firmwareVersion", "0.2.0"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("deviceId").asText();
    }

    private String create(String token, String left, String right) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/measurement-sessions")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "leftDeviceId", left, "rightDeviceId", right, "sampleRateHz", 50))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("sessionId").asText();
    }

    private String batch(String leftId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "schemaVersion", "1.1", "receiverId", "GATEWAY-DEV-001", "batchId", "batch-1",
                "sentAt", "2026-09-04T01:00:05Z",
                "frames", List.of(Map.of("deviceId", leftId, "footSide", "LEFT", "sequence", 0,
                        "deviceTimeMs", 0, "sensorValues", List.of(120, 110, 60, 55, 70, 65, 60, 40)))));
    }

    private String receiverStatus(String state, int pending) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "receiverId", "GATEWAY-DEV-001", "state", state, "pendingBatchCount", pending,
                "observedAt", "2026-09-04T01:00:10Z"));
    }

    private static String points() {
        return """
                [{"index":0,"label":"S01","x":0.40,"y":0.88,"region":"HEEL","medialLateral":"MEDIAL"},
                 {"index":1,"label":"S02","x":0.62,"y":0.88,"region":"HEEL","medialLateral":"LATERAL"},
                 {"index":2,"label":"S03","x":0.36,"y":0.62,"region":"MIDFOOT","medialLateral":"MEDIAL"},
                 {"index":3,"label":"S04","x":0.66,"y":0.62,"region":"MIDFOOT","medialLateral":"LATERAL"},
                 {"index":4,"label":"S05","x":0.32,"y":0.36,"region":"FOREFOOT","medialLateral":"MEDIAL"},
                 {"index":5,"label":"S06","x":0.50,"y":0.34,"region":"FOREFOOT","medialLateral":"CENTER"},
                 {"index":6,"label":"S07","x":0.70,"y":0.38,"region":"FOREFOOT","medialLateral":"LATERAL"},
                 {"index":7,"label":"S08","x":0.36,"y":0.12,"region":"TOE","medialLateral":"MEDIAL"}]
                """;
    }
}
