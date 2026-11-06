package ma.kulha.identity.interfaces.rest;

import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.identity.application.AuthenticationService;
import ma.kulha.identity.application.GoogleAuthenticationService;
import ma.kulha.identity.application.PasswordResetService;
import ma.kulha.identity.application.RegisterCommand;
import ma.kulha.identity.application.RegistrationService;
import ma.kulha.identity.application.TokenPair;
import ma.kulha.identity.interfaces.dto.EmailStatusRequest;
import ma.kulha.identity.interfaces.dto.EmailStatusResponse;
import ma.kulha.identity.interfaces.dto.ForgotPasswordRequest;
import ma.kulha.identity.interfaces.dto.GoogleLoginRequest;
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
    private final GoogleAuthenticationService googleAuthentication;

    AuthController(RegistrationService registration,
                   AuthenticationService authentication,
                   PasswordResetService passwordReset,
                   GoogleAuthenticationService googleAuthentication) {
        this.registration = registration;
        this.authentication = authentication;
        this.passwordReset = passwordReset;
        this.googleAuthentication = googleAuthentication;
    }

    @PostMapping("/email-status")
    EmailStatusResponse emailStatus(@Valid @RequestBody EmailStatusRequest request) {
        RegistrationService.EmailStatus status = registration.checkEmail(request.email());
        return new EmailStatusResponse(status.exists(), status.verified());
    }

    /** Always 202: the response never reveals whether the email was already registered. */
    @PostMapping("/register")
    ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        registration.register(new RegisterCommand(
                request.email(), request.password(), request.fullName(), request.phone()));
        return ResponseEntity.accepted().build();
    }

    /**
     * Confirms the email address and signs the person in: 200 with the usual token pair. When the account
     * cannot sign in (for example it is disabled) the address is still confirmed, but the answer is 204
     * and no session is issued. A bad, used or expired link is 400.
     */
    @PostMapping("/verify-email")
    ResponseEntity<TokenResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        UUID userId = registration.verifyEmail(request.token());
        Optional<TokenPair> session = authentication.issueSessionAfterEmailVerification(userId);
        if (session.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(TokenResponse.from(session.get()));
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

    @PostMapping("/google")
    TokenResponse googleLogin(@Valid @RequestBody GoogleLoginRequest request,
                              @org.springframework.web.bind.annotation.RequestHeader(value = "X-Kulha-Bff-Secret", required = false) String bffSecret) {
        return TokenResponse.from(googleAuthentication.login(request.idToken(), request.nonce(), bffSecret));
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
