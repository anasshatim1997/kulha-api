package ma.kulha.admin.support.interfaces.rest;

import java.util.UUID;
import ma.kulha.admin.support.application.SupportCaseService;
import ma.kulha.admin.support.interfaces.dto.OpenSupportCaseRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/support/cases")
public class SupportCaseController {
    private final SupportCaseService cases;
    SupportCaseController(SupportCaseService cases) { this.cases = cases; }
    @PostMapping
    SupportCaseService.CaseDetail open(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody OpenSupportCaseRequest request) {
        return cases.open(UUID.fromString(jwt.getSubject()), request);
    }
}
