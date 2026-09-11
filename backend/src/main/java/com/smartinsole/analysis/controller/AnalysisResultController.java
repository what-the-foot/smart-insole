package com.smartinsole.analysis.controller;

import com.smartinsole.analysis.service.AnalysisResultQueryService;
import com.smartinsole.global.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/measurement-sessions/{sessionId}/result")
public class AnalysisResultController {
    private final AnalysisResultQueryService service;

    public AnalysisResultController(AnalysisResultQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Object> result(@PathVariable UUID sessionId,
                                         @AuthenticationPrincipal AuthenticatedUser user) {
        return service.result(sessionId, user.userId());
    }
}
