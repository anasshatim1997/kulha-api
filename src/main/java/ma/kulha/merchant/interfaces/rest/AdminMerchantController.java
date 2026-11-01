package ma.kulha.merchant.interfaces.rest;

import jakarta.validation.Valid;

import java.util.Objects;
import java.util.UUID;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.merchant.application.PageResult;
import ma.kulha.merchant.application.AdminMerchantView;
import ma.kulha.merchant.domain.model.MerchantStatus;
import ma.kulha.merchant.interfaces.dto.AdminMerchantResponse;
import ma.kulha.merchant.interfaces.dto.DecisionReasonRequest;
import ma.kulha.shared.web.PageResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Only users with the ADMIN role reach this controller (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/merchants")
public class AdminMerchantController {

    private static final int MAX_PAGE_SIZE = 50;

    private final MerchantService merchants;

    AdminMerchantController(MerchantService merchants) {
        this.merchants = merchants;
    }

    @GetMapping
    PageResponse<AdminMerchantResponse> list(@RequestParam(required = false) MerchantStatus status,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        PageResult<AdminMerchantView> result = merchants.list(status, safePage, safeSize);
        return new PageResponse<>(
                result.items().stream().map(AdminMerchantResponse::from).toList(),
                safePage, safeSize, result.total());
    }

    @GetMapping("/{id}")
    AdminMerchantResponse get(@PathVariable UUID id) {
        return AdminMerchantResponse.from(merchants.get(id));
    }

    @PostMapping("/{id}/approve")
    AdminMerchantResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return AdminMerchantResponse.from(merchants.approve(UUID.fromString(Objects.requireNonNull(jwt.getSubject())), id));
    }

    @PostMapping("/{id}/reject")
    AdminMerchantResponse reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                 @Valid @RequestBody DecisionReasonRequest request) {
        return AdminMerchantResponse.from(merchants.reject(UUID.fromString(Objects.requireNonNull(jwt.getSubject())), id, request.reason()));
    }

    @PostMapping("/{id}/suspend")
    AdminMerchantResponse suspend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                  @Valid @RequestBody DecisionReasonRequest request) {
        return AdminMerchantResponse.from(merchants.suspend(UUID.fromString(Objects.requireNonNull(jwt.getSubject())), id, request.reason()));
    }
}
