package com.smartinsole.auth;

import com.smartinsole.auth.AuthDtos.AuthTokenResponse;
import com.smartinsole.auth.AuthDtos.SigninRequest;
import com.smartinsole.auth.AuthDtos.SignupRequest;
import com.smartinsole.auth.AuthDtos.UserSummary;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.global.security.JwtService;
import com.smartinsole.user.UserAccount;
import com.smartinsole.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Clock clock;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.clock = clock;
    }

    @Transactional
    public UserSummary signup(SignupRequest request) {
        validateBcryptLength(request.password());
        String email = normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        UserAccount user = UserAccount.create(email, passwordEncoder.encode(request.password()),
                request.name().trim(), Instant.now(clock));
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        return summary(user);
    }

    @Transactional(readOnly = true)
    public AuthTokenResponse signin(SigninRequest request) {
        validateBcryptLength(request.password());
        UserAccount user = users.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        JwtService.Token token = jwtService.issue(user.getId(), user.getEmail());
        return new AuthTokenResponse("Bearer", token.value(), token.expiresInSeconds(), summary(user));
    }

    private static UserSummary summary(UserAccount user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getName(), user.getCreatedAt());
    }

    private static String normalizeEmail(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static void validateBcryptLength(String password) {
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 72) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.",
                    Map.of("maxUtf8Bytes", 72, "actualUtf8Bytes", bytes));
        }
    }
}
