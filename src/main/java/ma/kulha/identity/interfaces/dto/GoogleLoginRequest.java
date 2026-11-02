package ma.kulha.identity.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GoogleLoginRequest(@NotBlank @Size(max = 8192) String idToken,
                                 @NotBlank @Size(max = 128) String nonce) {
}
