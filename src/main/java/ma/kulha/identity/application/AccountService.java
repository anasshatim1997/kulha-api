package ma.kulha.identity.application;

import java.util.Optional;
import java.util.UUID;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final UserRepository users;

    AccountService(UserRepository users) {
        this.users = users;
    }

    public Optional<User> findById(UUID userId) {
        return users.findById(userId);
    }
}
