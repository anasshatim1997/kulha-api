package ma.kulha.identity.application;

import java.util.Optional;
import java.util.UUID;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final UserRepository users;

    AccountService(UserRepository users) {
        this.users = users;
    }

    public Optional<User> findById(UUID userId) {
        return users.findById(userId);
    }

    @Transactional
    public Optional<User> updateProfile(UUID userId, String fullName, String phone) {
        String cleanName = fullName == null ? "" : fullName.trim();
        String cleanPhone = phone == null || phone.isBlank() ? null : phone.trim();
        if (cleanName.isBlank() || cleanName.length() > 120) {
            throw new IllegalArgumentException("Full name must contain between 1 and 120 characters.");
        }
        if (cleanPhone != null && cleanPhone.length() > 30) {
            throw new IllegalArgumentException("Phone number must be 30 characters or fewer.");
        }
        if (users.updateProfile(userId, cleanName, cleanPhone) == 0) {
            return Optional.empty();
        }
        return users.findById(userId);
    }
}
