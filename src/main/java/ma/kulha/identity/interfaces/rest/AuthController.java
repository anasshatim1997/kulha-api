package ma.kulha.identity.interfaces.rest;

import jakarta.validation.Valid;
import ma.kulha.identity.application.AuthenticationService;
import ma.kulha.identity.application.PasswordResetService;
import ma.kulha.identity.application.RegisterCommand;
import ma.kulha.identity.application.RegistrationService;
import ma.kulha.identity.interfaces.dto.ForgotPasswordRequest;
import ma.kulha.identity.interfaces.dto.LoginRequest;
import ma.kulha.identity.interfaces.dto.RefreshRequest;
import ma.kulha.identity.interfaces.dto.RegisterRequest;
import ma.kulha.identity.interfaces.dto.ResendVerificationRequest;
import ma.kulha.identity.interfaces.dto.ResetPasswordRequest;
import ma.kulha.identity.interfaces.dto.TokenResponse;
import ma.kulha.identity.interfaces.dto.VerifyEmailRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationService registration;
    private final AuthenticationService authentication;
    private final PasswordResetService passwordReset;

    AuthController(RegistrationService registration,
                   AuthenticationService authentication,
                   PasswordResetService passwordReset) {
        this.registration = registration;
        this.authentication = authentication;
        this.passwordReset = passwordReset;
    }

    /** Always 202: the response never reveals whether the email was already registered. */
    @PostMapping("/register")
    ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        registration.register(new RegisterCommand(
                request.email(), request.password(), request.fullName(), request.phone()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/verify-email")
    ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        registration.verifyEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    /** Always 202, whether or not the account exists. */
    @PostMapping("/resend-verification")
    ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        registration.resendVerification(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return TokenResponse.from(authentication.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.from(authentication.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authentication.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** Always 202, whether or not the account exists. */
    @PostMapping("/forgot-password")
    ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordReset.requestReset(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset-password")
    ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordReset.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
