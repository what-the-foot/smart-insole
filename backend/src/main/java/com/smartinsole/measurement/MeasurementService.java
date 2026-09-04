package com.smartinsole.measurement;

import com.smartinsole.analysis.AnalysisJob;
import com.smartinsole.analysis.AnalysisJobRepository;
import com.smartinsole.calibration.CalibrationProfile;
import com.smartinsole.calibration.CalibrationProfileRepository;
import com.smartinsole.device.Device;
import com.smartinsole.device.DeviceRepository;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.config.AnalysisProperties;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.measurement.MeasurementDtos.CreateMeasurementSessionRequest;
import com.smartinsole.measurement.MeasurementDtos.MeasurementSessionPage;
import com.smartinsole.measurement.MeasurementDtos.MeasurementSessionResponse;
import com.smartinsole.measurement.MeasurementDtos.MeasurementHistoryItem;
import com.smartinsole.measurement.MeasurementDtos.SessionClosedEvent;
import com.smartinsole.measurement.MeasurementDtos.SessionCompletedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeasurementService {
    private final MeasurementSessionRepository sessions;
    private final DeviceRepository devices;
    private final CalibrationProfileRepository calibrations;
    private final AnalysisJobRepository jobs;
    private final AnalysisProperties analysisProperties;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final HistoryProjectionRepository historyProjections;
    private final QualityService qualityService;

    public MeasurementService(MeasurementSessionRepository sessions, DeviceRepository devices,
                              CalibrationProfileRepository calibrations, AnalysisJobRepository jobs,
                              AnalysisProperties analysisProperties, ApplicationEventPublisher events, Clock clock,
                              HistoryProjectionRepository historyProjections, QualityService qualityService) {
        this.sessions = sessions;
        this.devices = devices;
        this.calibrations = calibrations;
        this.jobs = jobs;
        this.analysisProperties = analysisProperties;
        this.events = events;
        this.clock = clock;
        this.historyProjections = historyProjections;
        this.qualityService = qualityService;
    }

    @Transactional
    public MeasurementSessionResponse create(UUID userId, CreateMeasurementSessionRequest request) {
        if (request.sampleRateHz() != 100 || request.leftDeviceId().equals(request.rightDeviceId())) {
            throw new BusinessException(ErrorCode.INVALID_DEVICE_SELECTION);
        }
        Device left = ownedDevice(request.leftDeviceId(), userId);
        Device right = ownedDevice(request.rightDeviceId(), userId);
        if (left.getFootSide() != FootSide.LEFT || right.getFootSide() != FootSide.RIGHT) {
            throw new BusinessException(ErrorCode.INVALID_DEVICE_SELECTION);
        }
        CalibrationProfile leftCalibration = calibrations
                .findFirstByDeviceIdAndActiveTrueOrderByCreatedAtDesc(left.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_DEVICE_SELECTION,
                        "왼발 기기에 활성 보정 프로필이 없습니다."));
        CalibrationProfile rightCalibration = calibrations
                .findFirstByDeviceIdAndActiveTrueOrderByCreatedAtDesc(right.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_DEVICE_SELECTION,
                        "오른발 기기에 활성 보정 프로필이 없습니다."));
        String memo = request.memo() == null ? null : request.memo().trim();
        MeasurementSession session = MeasurementSession.create(userId, left.getId(), right.getId(),
                leftCalibration.getId(), rightCalibration.getId(), left.getSensorLayoutVersion(),
                right.getSensorLayoutVersion(), 100, memo, Instant.now(clock));
        return response(sessions.save(session));
    }

    @Transactional
    public MeasurementSessionResponse start(UUID sessionId, UUID userId) {
        MeasurementSession session = ownedSessionForUpdate(sessionId, userId);
        session.start(Instant.now(clock));
        return response(session);
    }

    @Transactional
    public MeasurementSessionResponse complete(UUID sessionId, UUID userId) {
        MeasurementSession session = ownedSessionForUpdate(sessionId, userId);
        Instant now = Instant.now(clock);
        session.complete(now);
        qualityService.finalizeSession(session, now);
        jobs.findBySessionIdAndAlgorithmVersion(sessionId, analysisProperties.algorithmVersion())
                .orElseGet(() -> jobs.save(AnalysisJob.pending(sessionId,
                        analysisProperties.algorithmVersion(), now)));
        events.publishEvent(new SessionCompletedEvent(sessionId));
        events.publishEvent(new SessionClosedEvent(sessionId));
        return response(session);
    }

    @Transactional
    public MeasurementSessionResponse cancel(UUID sessionId, UUID userId) {
        MeasurementSession session = ownedSessionForUpdate(sessionId, userId);
        session.cancel(Instant.now(clock));
        events.publishEvent(new SessionClosedEvent(sessionId));
        return response(session);
    }

    @Transactional(readOnly = true)
    public MeasurementSessionResponse get(UUID sessionId, UUID userId) {
        return response(ownedSession(sessionId, userId));
    }

    @Transactional(readOnly = true)
    public MeasurementSessionPage list(UUID userId, int page, int size, MeasurementStatus status,
                                       Instant from, Instant to, Integer minQualityScore, String patternCode) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (from != null && to != null && from.isAfter(to)
                || minQualityScore != null && (minQualityScore < 0 || minQualityScore > 100)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String normalizedPattern = patternCode == null || patternCode.isBlank() ? null : patternCode.trim();
        if (normalizedPattern != null && normalizedPattern.length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        PageRequest pageable = PageRequest.of(page, size);
        Page<MeasurementSession> result = sessions.searchHistory(userId.toString(),
                status == null ? null : status.name(), from, to, minQualityScore, normalizedPattern, pageable);
        Map<UUID, String> primaryPatterns = historyProjections.primaryPatterns(
                result.getContent().stream().map(MeasurementSession::getId).toList());
        return new MeasurementSessionPage(result.getContent().stream()
                .map(session -> historyItem(session, primaryPatterns.get(session.getId()))).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public MeasurementSession ownedSession(UUID sessionId, UUID userId) {
        return sessions.findByIdAndUserId(sessionId, userId).orElseThrow(() ->
                sessions.existsById(sessionId)
                        ? new BusinessException(ErrorCode.ACCESS_DENIED)
                        : new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private MeasurementSession ownedSessionForUpdate(UUID sessionId, UUID userId) {
        MeasurementSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return session;
    }

    private Device ownedDevice(UUID deviceId, UUID userId) {
        return devices.findByIdAndUserId(deviceId, userId).orElseThrow(() ->
                devices.existsById(deviceId)
                        ? new BusinessException(ErrorCode.ACCESS_DENIED)
                        : new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public static MeasurementSessionResponse response(MeasurementSession session) {
        return new MeasurementSessionResponse(session.getId(), session.getStatus(), session.getLeftDeviceId(),
                session.getRightDeviceId(), session.getSampleRateHz(), session.getSourceType(), session.getMemo(),
                session.getDataQualityScore(), session.getStartedAt(), session.getEndedAt(), session.getCreatedAt());
    }

    private static MeasurementHistoryItem historyItem(MeasurementSession session, String primaryPatternCode) {
        return new MeasurementHistoryItem(session.getId(), session.getStatus(), session.getLeftDeviceId(),
                session.getRightDeviceId(), session.getSampleRateHz(), session.getSourceType(), session.getMemo(),
                session.getDataQualityScore(), session.getStartedAt(), session.getEndedAt(), session.getCreatedAt(),
                primaryPatternCode);
    }
}
