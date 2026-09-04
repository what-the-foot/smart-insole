package com.smartinsole.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.realtime.disconnect-scan-enabled", matchIfMissing = true)
public class RealtimeDisconnectScheduler {
    private final RealtimeService realtimeService;

    public RealtimeDisconnectScheduler(RealtimeService realtimeService) {
        this.realtimeService = realtimeService;
    }

    @Scheduled(fixedDelayString = "${app.realtime.disconnect-scan-interval-ms:500}")
    public void publishConnectionTransitions() {
        realtimeService.publishConnectionTransitions();
    }
}
