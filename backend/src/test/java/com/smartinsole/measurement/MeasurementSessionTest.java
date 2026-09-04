package com.smartinsole.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.error.BusinessException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.smartinsole.support.TestSessions;

class MeasurementSessionTest {
    private final Instant now = Instant.parse("2026-09-02T07:00:00Z");

    @Test
    void followsAllowedLifecycle() {
        MeasurementSession session = session();

        session.start(now.plusSeconds(1));
        session.complete(now.plusSeconds(10));
        session.analysisCompleted(92, now.plusSeconds(11));

        assertThat(session.getStatus()).isEqualTo(MeasurementStatus.COMPLETED);
        assertThat(session.getDataQualityScore()).isEqualTo(92);
    }

    @Test
    void repeatedOrOutOfOrderTransitionIsConflict() {
        MeasurementSession session = session();

        assertThatThrownBy(() -> session.complete(now.plusSeconds(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code().name())
                .isEqualTo("INVALID_SESSION_STATE");

        session.start(now.plusSeconds(1));
        assertThatThrownBy(() -> session.start(now.plusSeconds(2)))
                .isInstanceOf(BusinessException.class);
    }

    private MeasurementSession session() {
        return TestSessions.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100, null, now);
    }
}
