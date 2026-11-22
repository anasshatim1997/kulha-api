package ma.kulha.admin.interfaces.rest;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import ma.kulha.admin.application.AdminAuditQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/admin/audit-log")
public class AdminAuditController {
    private final AdminAuditQueryService audit;
    AdminAuditController(AdminAuditQueryService audit) { this.audit = audit; }

    @GetMapping
    AdminAuditQueryService.AuditPage list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return audit.list(from, to, actorId, action, result, page, size);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    ResponseEntity<StreamingResponseBody> export(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result) {
        UUID adminId = UUID.fromString(jwt.getSubject());
        StreamingResponseBody body = outputStream -> {
            var writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            audit.writeExport(adminId, from, to, actorId, action, result, writer);
            writer.flush();
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=kulha-audit-log.csv").body(body);
    }
}
