package ma.kulha.identity.interfaces.dto;

import java.util.List;
import java.util.UUID;
import ma.kulha.identity.domain.model.User;

public record MeResponse(UUID id, String email, String fullName, String phone,
                         boolean emailVerified, List<String> roles) {

    public static MeResponse from(User user) {
        return new MeResponse(
                user.id(), user.email(), user.fullName(), user.phone(), user.isEmailVerified(),
                user.roles().stream().map(Enum::name).sorted().toList());
    }
}
