package com.smartinsole.measurement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.error.BusinessException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.smartinsole.support.TestSessions;
import com.smartinsole.global.common.DomainTypes.ReceiverUploadState;
import com.smartinsole.global.common.DomainTypes.SourceType;

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

    @Test
    void recordsReceiverStatusAndIgnoresStaleReports() {
        MeasurementSession session = session();
        session.start(now.plusSeconds(1));

        session.recordReceiverStatus("GATEWAY-DEV-001", ReceiverUploadState.STREAMING, 0, now.plusSeconds(5));
        session.recordReceiverStatus("GATEWAY-DEV-001", ReceiverUploadState.UPLOADING, 2, now.plusSeconds(3));

        assertThat(session.getReceiverId()).isEqualTo("GATEWAY-DEV-001");
        assertThat(session.getReceiverState()).isEqualTo(ReceiverUploadState.STREAMING);
        assertThat(session.getReceiverPendingBatches()).isZero();
        assertThat(session.getReceiverObservedAt()).isEqualTo(now.plusSeconds(5));
    }

    @Test
    void requiresAnExplicitSourceTypeAndAPositiveAdcScale() {
        assertThatThrownBy(() -> MeasurementSession.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 50, null, 4095, null, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MeasurementSession.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 50, SourceType.DEVICE, 0, null, now))
                .isInstanceOf(IllegalArgumentException.class);
        MeasurementSession device = MeasurementSession.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 50, SourceType.DEVICE, 4095, null, now);
        assertThat(device.getSourceType()).isEqualTo(SourceType.DEVICE);
        assertThat(device.getAdcMax()).isEqualTo(4095);
        assertThat(device.getSampleRateHz()).isEqualTo(50);
    }

    private MeasurementSession session() {
        return TestSessions.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100, null, now);
    }
}
