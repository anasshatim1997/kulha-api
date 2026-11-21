package ma.kulha.support.interfaces.rest;

import java.util.UUID;
import jakarta.validation.Valid;
import ma.kulha.support.application.SupportCaseService;
import ma.kulha.support.interfaces.dto.AssignSupportCaseRequest;
import ma.kulha.support.interfaces.dto.SupportCaseActionRequest;
import ma.kulha.support.interfaces.dto.SupportCaseMessageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/support-cases")
public class AdminSupportCaseController {
    private final SupportCaseService cases;
    AdminSupportCaseController(SupportCaseService cases) { this.cases = cases; }

    @GetMapping
    SupportCaseService.CasePage list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return cases.list(status, category, page, size);
    }

    @GetMapping("/{reference}")
    SupportCaseService.CaseDetail get(@PathVariable String reference) { return cases.get(reference); }

    @PostMapping("/{reference}/messages")
    void message(@AuthenticationPrincipal Jwt jwt, @PathVariable String reference,
            @Valid @RequestBody SupportCaseMessageRequest request) {
        cases.addMessage(UUID.fromString(jwt.getSubject()), reference, request.message(), request.sendToCustomer());
    }

    @PutMapping("/{reference}/assignee")
    void assign(@AuthenticationPrincipal Jwt jwt, @PathVariable String reference,
            @Valid @RequestBody AssignSupportCaseRequest request) {
        cases.assign(UUID.fromString(jwt.getSubject()), reference, request.assignedTo());
    }

    @PostMapping("/{reference}/actions")
    void action(@AuthenticationPrincipal Jwt jwt, @PathVariable String reference,
            @Valid @RequestBody SupportCaseActionRequest request) {
        cases.act(UUID.fromString(jwt.getSubject()), reference, request.action(), request.reason());
    }

    @PostMapping("/{reference}/reveal-customer")
    SupportCaseService.CustomerDetails reveal(@AuthenticationPrincipal Jwt jwt, @PathVariable String reference) {
        return cases.revealCustomer(UUID.fromString(jwt.getSubject()), reference);
    }
}
