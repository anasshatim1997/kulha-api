package ma.kulha.identity.application;

import java.util.Optional;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
class IdentityApiService implements IdentityApi {

    private final UserRepository users;

    IdentityApiService(UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean isEmailVerified(UUID userId) {
        return users.findById(userId).map(User::isEmailVerified).orElse(false);
    }

    @Override
    public Optional<String> emailOf(UUID userId) {
        return users.findById(userId).map(User::email);
    }

    @Override
    public void grantMerchantRole(UUID userId) {
        users.addRole(userId, Role.MERCHANT);
    }

    @Override
    public void revokeMerchantRole(UUID userId) {
        users.removeRole(userId, Role.MERCHANT);
    }
}
