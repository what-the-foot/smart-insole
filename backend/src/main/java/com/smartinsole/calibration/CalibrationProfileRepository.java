package com.smartinsole.calibration;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalibrationProfileRepository extends JpaRepository<CalibrationProfile, UUID> {
    Optional<CalibrationProfile> findFirstByDeviceIdAndActiveTrueOrderByCreatedAtDesc(UUID deviceId);
    List<CalibrationProfile> findAllByDeviceIdInAndActiveTrue(Collection<UUID> deviceIds);
}
