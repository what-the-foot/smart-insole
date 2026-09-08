package com.smartinsole.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartinsole.auth.AuthDtos.SignupRequest;
import com.smartinsole.global.config.SeedAccountProperties;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeedAccountInitializerTest {
    @Mock UserRepository users;
    @Mock AuthService authService;

    @Test
    void createsTheAccountWhenConfiguredAndMissing() {
        SeedAccountProperties properties =
                new SeedAccountProperties(" Admin@Example.com ", "local-only-pass", " Admin ");
        when(users.existsByEmail("admin@example.com")).thenReturn(false);

        boolean created = new SeedAccountInitializer(properties, users, authService).seed();

        assertThat(created).isTrue();
        verify(authService).signup(new SignupRequest("admin@example.com", "local-only-pass", "Admin"));
    }

    @Test
    void doesNothingWhenEmailOrPasswordIsBlank() {
        SeedAccountProperties properties = new SeedAccountProperties("", "", null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.name()).isEqualTo(SeedAccountProperties.DEFAULT_NAME);
        assertThat(new SeedAccountInitializer(properties, users, authService).seed()).isFalse();
        verifyNoInteractions(users, authService);
    }

    @Test
    void skipsWhenTheAccountAlreadyExists() {
        SeedAccountProperties properties =
                new SeedAccountProperties("admin@example.com", "local-only-pass", "Admin");
        when(users.existsByEmail("admin@example.com")).thenReturn(true);

        assertThat(new SeedAccountInitializer(properties, users, authService).seed()).isFalse();
        verify(authService, never()).signup(any());
    }

    @Test
    void rejectsShortPasswordsAndMalformedEmailsWithoutTouchingTheDatabase() {
        SeedAccountProperties shortPassword =
                new SeedAccountProperties("admin@example.com", "short", "Admin");
        SeedAccountProperties notAnEmail =
                new SeedAccountProperties("admin", "local-only-pass", "Admin");

        assertThat(new SeedAccountInitializer(shortPassword, users, authService).seed()).isFalse();
        assertThat(new SeedAccountInitializer(notAnEmail, users, authService).seed()).isFalse();
        verifyNoInteractions(users, authService);
    }

    @Test
    void reportsFalseWhenSignupRejectsTheRequest() {
        SeedAccountProperties properties =
                new SeedAccountProperties("admin@example.com", "x".repeat(80), "Admin");
        when(users.existsByEmail("admin@example.com")).thenReturn(false);
        when(authService.signup(any())).thenThrow(new BusinessException(ErrorCode.INVALID_REQUEST));

        assertThat(new SeedAccountInitializer(properties, users, authService).seed()).isFalse();
    }
}
