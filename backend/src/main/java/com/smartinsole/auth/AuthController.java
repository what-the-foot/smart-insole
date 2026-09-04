package com.smartinsole.auth;

import com.smartinsole.auth.AuthDtos.AuthTokenResponse;
import com.smartinsole.auth.AuthDtos.SigninRequest;
import com.smartinsole.auth.AuthDtos.SignupRequest;
import com.smartinsole.auth.AuthDtos.UserSummary;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserSummary signup(@Valid @RequestBody SignupRequest request) {
        return service.signup(request);
    }

    @PostMapping("/signin")
    public AuthTokenResponse signin(@Valid @RequestBody SigninRequest request) {
        return service.signin(request);
    }
}
