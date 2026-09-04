package com.smartinsole.measurement;

import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.security.AuthenticatedUser;
import com.smartinsole.measurement.MeasurementDtos.CreateMeasurementSessionRequest;
import com.smartinsole.measurement.MeasurementDtos.MeasurementSessionPage;
import com.smartinsole.measurement.MeasurementDtos.MeasurementSessionResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/measurement-sessions")
public class MeasurementController {
    private final MeasurementService service;

    public MeasurementController(MeasurementService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MeasurementSessionResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                             @Valid @RequestBody CreateMeasurementSessionRequest request) {
        return service.create(user.userId(), request);
    }

    @GetMapping
    public MeasurementSessionPage list(@AuthenticationPrincipal AuthenticatedUser user,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(required = false) MeasurementStatus status,
                                       @RequestParam(required = false) Instant from,
                                       @RequestParam(required = false) Instant to,
                                       @RequestParam(required = false) Integer minQualityScore,
                                       @RequestParam(required = false) String patternCode) {
        return service.list(user.userId(), page, size, status, from, to, minQualityScore, patternCode);
    }

    @GetMapping("/{sessionId}")
    public MeasurementSessionResponse get(@PathVariable UUID sessionId,
                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return service.get(sessionId, user.userId());
    }

    @PostMapping("/{sessionId}/start")
    public MeasurementSessionResponse start(@PathVariable UUID sessionId,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.start(sessionId, user.userId());
    }

    @PostMapping("/{sessionId}/complete")
    public MeasurementSessionResponse complete(@PathVariable UUID sessionId,
                                               @AuthenticationPrincipal AuthenticatedUser user) {
        return service.complete(sessionId, user.userId());
    }

    @PostMapping("/{sessionId}/cancel")
    public MeasurementSessionResponse cancel(@PathVariable UUID sessionId,
                                             @AuthenticationPrincipal AuthenticatedUser user) {
        return service.cancel(sessionId, user.userId());
    }
}
