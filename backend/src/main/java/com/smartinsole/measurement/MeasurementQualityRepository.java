package com.smartinsole.measurement;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeasurementQualityRepository extends JpaRepository<MeasurementQualityStats, UUID> {
}
