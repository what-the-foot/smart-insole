package com.smartinsole.measurement.controller;

import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.measurement.dto.IngestionDtos.ReceiverSessionListResponse;
import com.smartinsole.measurement.dto.IngestionDtos.ReceiverSessionResponse;
import com.smartinsole.measurement.dto.IngestionDtos.ReceiverStatusRequest;
import com.smartinsole.measurement.service.PressureFrameIngestionService;
import com.smartinsole.measurement.service.ReceiverSessionQueryService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receiver-facing session endpoints. Every /internal/v1/** request passes ReceiverApiKeyFilter
 * (X-Receiver-Key) before reaching this controller.
 */
@RestController
@RequestMapping("/internal/v1/measurement-sessions")
public class ReceiverSessionController {
    private final ReceiverSessionQueryService queries;
    private final PressureFrameIngestionService ingestion;

    public ReceiverSessionController(ReceiverSessionQueryService queries,
                                     PressureFrameIngestionService ingestion) {
        this.queries = queries;
        this.ingestion = ingestion;
    }

    @GetMapping
    public ReceiverSessionListResponse list(@RequestParam MeasurementStatus status,
                                            @RequestParam(required = false) String deviceSerial) {
        return queries.list(status, deviceSerial);
    }

    @GetMapping("/{sessionId}")
    public ReceiverSessionResponse get(@PathVariable UUID sessionId) {
        return queries.get(sessionId);
    }

    @PostMapping("/{sessionId}/receiver-status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receiverStatus(@PathVariable UUID sessionId, @Valid @RequestBody ReceiverStatusRequest request) {
        ingestion.recordReceiverStatus(sessionId, request);
    }
}
