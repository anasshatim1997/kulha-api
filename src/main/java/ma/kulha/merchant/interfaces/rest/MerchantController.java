package ma.kulha.merchant.interfaces.rest;

import jakarta.validation.Valid;

import java.util.Objects;
import java.util.UUID;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.merchant.interfaces.dto.MerchantProfileRequest;
import ma.kulha.merchant.interfaces.dto.MerchantResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final MerchantService merchants;

    MerchantController(MerchantService merchants) {
        this.merchants = merchants;
    }

    @PostMapping("/apply")
    ResponseEntity<MerchantResponse> apply(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody MerchantProfileRequest request) {
        var merchant = merchants.apply(UUID.fromString(Objects.requireNonNull(jwt.getSubject())), request.toProfile());
        return ResponseEntity.status(HttpStatus.CREATED).body(MerchantResponse.from(merchant));
    }

    @GetMapping("/me")
    MerchantResponse me(@AuthenticationPrincipal Jwt jwt) {
        return MerchantResponse.from(merchants.getOwn(UUID.fromString(Objects.requireNonNull(jwt.getSubject()))));
    }

    @PutMapping("/me")
    MerchantResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MerchantProfileRequest request) {
        return MerchantResponse.from(merchants.updateOwn(UUID.fromString(Objects.requireNonNull(jwt.getSubject())), request.toProfile()));
    }
}
