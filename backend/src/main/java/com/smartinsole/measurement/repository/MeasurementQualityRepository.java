package com.smartinsole.measurement.repository;

import com.smartinsole.measurement.domain.MeasurementQualityStats;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeasurementQualityRepository extends JpaRepository<MeasurementQualityStats, UUID> {
}
