package com.smartinsole.measurement.controller;

import com.smartinsole.measurement.dto.IngestionDtos.FrameBatchRequest;
import com.smartinsole.measurement.dto.IngestionDtos.FrameBatchResponse;
import com.smartinsole.measurement.service.PressureFrameIngestionService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/measurement-sessions/{sessionId}/frame-batches")
public class PressureFrameBatchController {
    private final PressureFrameIngestionService service;

    public PressureFrameBatchController(PressureFrameIngestionService service) {
        this.service = service;
    }

    @PostMapping
    public FrameBatchResponse ingest(@PathVariable UUID sessionId,
                                     @RequestBody FrameBatchRequest request) {
        return service.ingest(sessionId, request);
    }
}
