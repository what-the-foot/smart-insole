package com.smartinsole.realtime;

import com.smartinsole.global.security.AuthenticatedUser;
import com.smartinsole.realtime.RealtimeDtos.RealtimePressureMessage;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/measurement-sessions/{sessionId}/realtime-snapshot")
public class RealtimeController {
    private final RealtimeService service;

    public RealtimeController(RealtimeService service) {
        this.service = service;
    }

    @GetMapping
    public RealtimePressureMessage get(@PathVariable UUID sessionId,
                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return service.getSnapshot(sessionId, user.userId());
    }
}
