package ma.kulha.support.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SupportCaseActionRequest(
        @NotBlank @Pattern(regexp = "CONTACT_SHOP|ESCALATE|RESOLVE|KEEP_OFFER_HIDDEN") String action,
        @Size(max = 500) String reason) {
}
