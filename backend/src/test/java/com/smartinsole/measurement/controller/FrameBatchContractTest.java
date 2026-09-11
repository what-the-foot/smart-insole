package com.smartinsole.measurement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartinsole.device.domain.SensorLayout;
import com.smartinsole.device.repository.SensorLayoutRepository;
import com.smartinsole.measurement.repository.PressureFrameRepository.StoredPressureFrame;
import com.smartinsole.measurement.repository.PressureFrameRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Backend side of the contract test loop: the receiver serializer's real schemaVersion 1.1 output
 * (fixtures/frame-batch-device-v1_1.json) must be ingested completely, and the same frames must be
 * refused field-by-field when sent as a 1.0 batch.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:frame_batch_contract;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FrameBatchContractTest {
    private static final Path FIXTURE = Path.of("..", "fixtures", "frame-batch-device-v1_1.json");
    private static final String RECEIVER_KEY = "test-receiver-key";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SensorLayoutRepository layouts;
    @Autowired PressureFrameRepository frames;

    @Test
    void ingestsTheReceiverSerializerFixtureCompletelyAndRejectsItAsLegacySchema() throws Exception {
        layouts.save(new SensorLayout("layout-s01s08-v1", 8, labelledPoints(), true,
                Instant.parse("2026-09-04T00:00:00Z")));
        String token = signupAndSignin("contract@example.com");
        String leftId = register(token, "SMART-INSOLE-L-12345678", "LEFT");
        String rightId = register(token, "SMART-INSOLE-R-9abcdef0", "RIGHT");
        String sessionId = createAndStart(token, leftId, rightId);

        ObjectNode fixture = (ObjectNode) objectMapper.readTree(Files.readString(FIXTURE));
        ArrayNode fixtureFrames = (ArrayNode) fixture.get("frames");
        for (JsonNode frame : fixtureFrames) {
            ((ObjectNode) frame).put("deviceId", "LEFT".equals(frame.get("footSide").asText()) ? leftId : rightId);
        }
        assertThat(fixture.get("schemaVersion").asText()).isEqualTo("1.1");

        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", RECEIVER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fixture)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(fixtureFrames.size()))
                .andExpect(jsonPath("$.duplicateCount").value(0))
                .andExpect(jsonPath("$.rejectedCount").value(0))
                .andExpect(jsonPath("$.rejections").isEmpty());

        List<StoredPressureFrame> stored = frames.findBySessionOrdered(UUID.fromString(sessionId));
        assertThat(stored).hasSize(fixtureFrames.size());
        assertThat(stored).allSatisfy(frame -> {
            assertThat(frame.protocolVersion()).isEqualTo(1);
            assertThat(frame.receiverReceivedAt()).isNotNull();
            assertThat(frame.dataMode()).isEqualTo(com.smartinsole.global.common.DomainTypes.DataMode.RAW);
            assertThat(frame.calibrated()).isFalse();
            assertThat(frame.flags()).isNull();
            if (Boolean.TRUE.equals(frame.imuAvailable())) {
                assertThat(frame.accelMg()).hasSize(3);
                assertThat(frame.gyroDps10()).hasSize(3);
            } else {
                assertThat(frame.accelMg()).isNull();
                assertThat(frame.gyroDps10()).isNull();
            }
        });
        assertThat(stored).anyMatch(frame -> Boolean.FALSE.equals(frame.imuAvailable()));
        // The receiver unwrapped the u16 wire counter: sequences continue past the 16-bit boundary.
        assertThat(stored).anyMatch(frame -> frame.sequence() >= 1L << 16);

        // Replaying the same batch is idempotent on (session, device, sequence).
        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", RECEIVER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fixture)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(0))
                .andExpect(jsonPath("$.duplicateCount").value(fixtureFrames.size()));

        // The same frames declared as schemaVersion 1.0 carry 1.1-only fields: every frame is refused.
        ObjectNode legacy = fixture.deepCopy();
        legacy.put("schemaVersion", "1.0");
        legacy.remove("batchId");
        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", RECEIVER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(legacy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(0))
                .andExpect(jsonPath("$.rejectedCount").value(fixtureFrames.size()))
                .andExpect(jsonPath("$.rejections[0].code").value("SCHEMA_FIELD_NOT_ALLOWED"));

        // 4096 exceeds the session adcMax (4095) and an unknown schemaVersion is refused as a whole.
        ObjectNode saturated = fixture.deepCopy();
        ObjectNode first = (ObjectNode) saturated.get("frames").get(0);
        first.put("sequence", first.get("sequence").asLong() + 100_000);
        ArrayNode values = (ArrayNode) first.get("sensorValues");
        values.set(0, 4096);
        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", RECEIVER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(saturated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejections[0].frameIndex").value(0))
                .andExpect(jsonPath("$.rejections[0].code").value("INVALID_ADC_VALUE"));
        ObjectNode unsupported = fixture.deepCopy();
        unsupported.put("schemaVersion", "1.2");
        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", RECEIVER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unsupported)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_SCHEMA_VERSION"));

        // The receiver reads the same session back with its ADC scale and labelled layout.
        mvc.perform(get("/internal/v1/measurement-sessions/{id}", sessionId).header("X-Receiver-Key", RECEIVER_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MEASURING"))
                .andExpect(jsonPath("$.adcMax").value(4095))
                .andExpect(jsonPath("$.left.serialNumber").value("SMART-INSOLE-L-12345678"))
                .andExpect(jsonPath("$.left.sensorLayoutVersion").value("layout-s01s08-v1"))
                .andExpect(jsonPath("$.right.adcMax").value(4095));
        mvc.perform(get("/api/v1/sensor-layouts/layout-s01s08-v1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(8))
                .andExpect(jsonPath("$.points[7].label").value("S08"));

        // After completion a late batch is dropped with an explicit disposition.
        mvc.perform(post("/api/v1/measurement-sessions/{id}/complete", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/v1/measurement-sessions/{id}/frame-batches", sessionId)
                        .header("X-Receiver-Key", RECEIVER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fixture)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_MEASURING"))
                .andExpect(jsonPath("$.details.disposition").value("DROP"))
                .andExpect(header().string("X-Batch-Disposition", "DROP"))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    private String signupAndSignin(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", "password123", "name", "Contract"))))
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
                                "firmwareVersion", "0.2.0", "adcMax", 4095))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adcMax").value(4095))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("deviceId").asText();
    }

    private String createAndStart(String token, String left, String right) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/measurement-sessions")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "leftDeviceId", left, "rightDeviceId", right, "sampleRateHz", 50))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("DEVICE"))
                .andExpect(jsonPath("$.sampleRateHz").value(50))
                .andExpect(jsonPath("$.adcMax").value(4095))
                .andReturn();
        String id = objectMapper.readTree(result.getResponse().getContentAsString()).path("sessionId").asText();
        mvc.perform(post("/api/v1/measurement-sessions/{id}/start", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MEASURING"));
        return id;
    }

    private static String labelledPoints() {
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
