package auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import auth.dto.AuthDto;
import auth.service.AuthService;
import jakarta.validation.Valid;

/**
 * Login helpers for testing (Bruno, Postman, curl). The frontend signs up and logs in to Cognito directly; these
 * endpoints only forward to the same Cognito app client, so they return exactly the tokens the frontend gets.
 * Sign-up is not offered here: a new user signs up through the frontend (Cognito) and then creates their profile with
 * {@code POST /user/me}. While email verification is switched off, the frontend confirms the new account with
 * {@code POST /auth/confirm-sign-up} instead of asking for the emailed code.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Public settings for the frontend, e.g. whether new accounts must enter the emailed code. */
    @GetMapping("/config")
    public ResponseEntity<AuthDto.ConfigResponse> config() {
        return ResponseEntity.ok(authService.config());
    }

    /**
     * Confirms a just-created account without the emailed code, while email verification is switched off
     * (EMAIL_VERIFICATION_ENABLED=false). 204; 403 while it is on.
     */
    @PostMapping("/confirm-sign-up")
    public ResponseEntity<Void> confirmSignUp(@Valid @RequestBody AuthDto.ConfirmSignUpRequest request) {
        authService.confirmWithoutCode(request.getUsername());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDto.TokenResponse> login(@Valid @RequestBody AuthDto.LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthDto.TokenResponse> refresh(@Valid @RequestBody AuthDto.RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody AuthDto.LogoutRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }
}
