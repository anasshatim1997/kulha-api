package ma.kulha.merchant.interfaces.rest;

import jakarta.validation.Valid;

import java.util.Objects;
import java.util.UUID;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.merchant.application.MerchantStaffService;
import ma.kulha.merchant.interfaces.dto.AddMerchantStaffRequest;
import ma.kulha.merchant.interfaces.dto.UpdateMerchantStaffRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;
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
    private final MerchantStaffService staff;

    MerchantController(MerchantService merchants, MerchantStaffService staff) {
        this.merchants = merchants;
        this.staff = staff;
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


    @GetMapping("/me/staff")
    List<MerchantStaffService.StaffMember> staff(@AuthenticationPrincipal Jwt jwt) {
        return staff.list(UUID.fromString(Objects.requireNonNull(jwt.getSubject())));
    }

    @PostMapping("/me/staff")
    ResponseEntity<MerchantStaffService.StaffMember> addStaff(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddMerchantStaffRequest request) {
        var member = staff.addPickupStaff(UUID.fromString(Objects.requireNonNull(jwt.getSubject())), request.email(),
                request.canValidatePickups(), request.canViewOrders(), request.canManageOffers());
        return ResponseEntity.status(HttpStatus.CREATED).body(member);
    }

    @PutMapping("/me/staff/{userId}")
    MerchantStaffService.StaffMember updateStaffPermissions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @Valid @RequestBody UpdateMerchantStaffRequest request) {
        return staff.updatePermissions(UUID.fromString(Objects.requireNonNull(jwt.getSubject())), userId,
                request.canValidatePickups(), request.canViewOrders(), request.canManageOffers());
    }

    @DeleteMapping("/me/staff/{userId}")
    ResponseEntity<Void> removeStaff(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        staff.remove(UUID.fromString(Objects.requireNonNull(jwt.getSubject())), userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/me")
    MerchantResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MerchantProfileRequest request) {
        return MerchantResponse.from(merchants.updateOwn(UUID.fromString(Objects.requireNonNull(jwt.getSubject())), request.toProfile()));
    }
}
