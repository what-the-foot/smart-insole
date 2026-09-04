package com.smartinsole.device;

import com.smartinsole.device.DeviceDtos.DeviceHeartbeatRequest;
import com.smartinsole.device.DeviceDtos.DeviceResponse;
import com.smartinsole.device.DeviceDtos.RegisterDeviceRequest;
import com.smartinsole.device.DeviceDtos.SensorLayoutResponse;
import com.smartinsole.global.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeviceController {
    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/devices")
    public List<DeviceResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.userId());
    }

    @PostMapping("/api/v1/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceResponse register(@AuthenticationPrincipal AuthenticatedUser user,
                                   @Valid @RequestBody RegisterDeviceRequest request) {
        return service.register(user.userId(), request);
    }

    @GetMapping("/api/v1/sensor-layouts/{version}")
    public SensorLayoutResponse layout(@PathVariable String version) {
        return service.layout(version);
    }

    @PostMapping("/internal/v1/devices/{deviceId}/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat(@PathVariable UUID deviceId,
                          @Valid @RequestBody DeviceHeartbeatRequest request) {
        service.heartbeat(deviceId, request);
    }
}
