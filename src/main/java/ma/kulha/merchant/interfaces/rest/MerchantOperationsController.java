package ma.kulha.merchant.interfaces.rest;

import java.time.LocalDate;
import java.util.UUID;
import ma.kulha.merchant.application.MerchantOperationsService;
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

@RestController
@RequestMapping("/api/v1/merchants/me")
public class MerchantOperationsController {
    private final MerchantOperationsService operations;
    MerchantOperationsController(MerchantOperationsService operations) { this.operations = operations; }

    @GetMapping("/workspace")
    MerchantOperationsService.Dashboard dashboard(@AuthenticationPrincipal Jwt jwt) {
        return operations.dashboard(userId(jwt));
    }

    @GetMapping("/orders")
    java.util.List<MerchantOperationsService.OrderRow> orders(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "ALL") String filter) {
        return operations.orders(userId(jwt), filter);
    }

    @GetMapping("/sales")
    MerchantOperationsService.Sales sales(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return operations.sales(userId(jwt), from, to);
    }

    @GetMapping(value = "/sales/export", produces = "text/csv")
    ResponseEntity<String> exportSales(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String csv = operations.exportSales(userId(jwt), from, to);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=kulha-sales.csv").body(csv);
    }

    private static UUID userId(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
