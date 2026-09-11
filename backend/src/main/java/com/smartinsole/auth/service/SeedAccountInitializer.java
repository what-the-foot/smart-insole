package com.smartinsole.auth.service;

import com.smartinsole.auth.dto.AuthDtos.SignupRequest;
import com.smartinsole.global.config.SeedAccountProperties;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.user.repository.UserRepository;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Creates the configured seed account once at startup (local profile only). The account has the
 * same permissions as any other user because the application has no role model. The password is
 * read from configuration and never logged.
 */
@Component
@Profile("local")
public class SeedAccountInitializer implements ApplicationRunner {
    static final int MIN_PASSWORD_LENGTH = 8;
    static final int MAX_NAME_LENGTH = 50;
    private static final Logger log = LoggerFactory.getLogger(SeedAccountInitializer.class);

    private final SeedAccountProperties properties;
    private final UserRepository users;
    private final AuthService authService;

    public SeedAccountInitializer(SeedAccountProperties properties, UserRepository users,
                                  AuthService authService) {
        this.properties = properties;
        this.users = users;
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /**
     * @return true when a new account was created; false when seeding is disabled, the
     *         configuration is invalid, or the account already exists.
     */
    boolean seed() {
        if (!properties.enabled()) {
            log.info("Seed account disabled: set SEED_ADMIN_EMAIL and SEED_ADMIN_PASSWORD to create one");
            return false;
        }
        String email = properties.email().toLowerCase(Locale.ROOT);
        if (!email.contains("@")) {
            log.warn("Seed account skipped: SEED_ADMIN_EMAIL is not an email address");
            return false;
        }
        if (properties.password().length() < MIN_PASSWORD_LENGTH) {
            log.warn("Seed account {} skipped: SEED_ADMIN_PASSWORD must be at least {} characters",
                    email, MIN_PASSWORD_LENGTH);
            return false;
        }
        if (properties.name().length() > MAX_NAME_LENGTH) {
            log.warn("Seed account {} skipped: SEED_ADMIN_NAME must be at most {} characters",
                    email, MAX_NAME_LENGTH);
            return false;
        }
        if (users.existsByEmail(email)) {
            log.info("Seed account {} already exists; nothing to do", email);
            return false;
        }
        try {
            authService.signup(new SignupRequest(email, properties.password(), properties.name()));
        } catch (BusinessException exception) {
            log.warn("Seed account {} skipped: {}", email, exception.getMessage());
            return false;
        }
        log.info("Seed account {} created", email);
        return true;
    }
}
