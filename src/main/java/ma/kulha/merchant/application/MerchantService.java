package ma.kulha.merchant.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import ma.kulha.admin.application.AuditApi;
import ma.kulha.identity.application.IdentityApi;
import ma.kulha.merchant.domain.model.InvalidMerchantStateException;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.merchant.domain.model.MerchantStatus;
import ma.kulha.merchant.domain.repository.MerchantRepository;
import ma.kulha.notification.application.NotificationApi;
import ma.kulha.notification.application.TemplateKey;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MerchantService {

    private final MerchantRepository merchants;
    private final IdentityApi identity;
    private final AuditApi audit;
    private final NotificationApi notifications;
    private final TransactionTemplate tx;
    private final Clock clock;

    MerchantService(MerchantRepository merchants, IdentityApi identity, AuditApi audit,
                    NotificationApi notifications, TransactionTemplate tx, Clock clock) {
        this.merchants = merchants;
        this.identity = identity;
        this.audit = audit;
        this.notifications = notifications;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------- the merchant's own side ----------

    /** A verified customer applies. The merchant starts as PENDING and gets no merchant rights yet. */
    public Merchant apply(UUID userId, Merchant.Profile profile) {
        if (!identity.isEmailVerified(userId)) {
            throw new EmailNotVerifiedException();
        }
        if (merchants.findByOwner(userId).isPresent()) {
            throw new MerchantAlreadyExistsException();
        }
        Merchant merchant = Merchant.apply(userId, profile, clock.instant());
        try {
            merchants.insert(merchant);
        } catch (DataIntegrityViolationException alreadyExists) {
            throw new MerchantAlreadyExistsException();
        }
        return merchant;
    }

    public Merchant getOwn(UUID userId) {
        return merchants.findByOwner(userId).orElseThrow(MerchantNotFoundException::new);
    }

    /** Edits the profile. A rejected merchant that corrects it is resubmitted as PENDING. */
    public Merchant updateOwn(UUID userId, Merchant.Profile profile) {
        Merchant current = getOwn(userId);
        Merchant updated = current.withProfile(profile, clock.instant());
        if (!merchants.updateIfStatus(updated, current.status())) {
            throw new InvalidMerchantStateException("The merchant was changed meanwhile. Reload and try again.");
        }
        return updated;
    }

    // ---------- administrators ----------

    public PageResult<AdminMerchantView> list(MerchantStatus statusOrNull, int page, int size) {
        List<AdminMerchantView> items = merchants.list(statusOrNull, size, page * size).stream()
                .map(this::toAdminView)
                .toList();
        return new PageResult<>(items, merchants.count(statusOrNull));
    }

    public AdminMerchantView get(UUID merchantId) {
        return merchants.findById(merchantId).map(this::toAdminView).orElseThrow(MerchantNotFoundException::new);
    }

    public AdminMerchantView approve(UUID adminId, UUID merchantId) {
        return decide(adminId, merchantId, "MERCHANT_APPROVED", null,
                (merchant, now) -> merchant.approve(adminId, now),
                identity::grantMerchantRole, TemplateKey.MERCHANT_APPROVED);
    }

    public AdminMerchantView reject(UUID adminId, UUID merchantId, String reason) {
        return decide(adminId, merchantId, "MERCHANT_REJECTED", reason,
                (merchant, now) -> merchant.reject(adminId, reason, now),
                ownerId -> { }, TemplateKey.MERCHANT_REJECTED);
    }

    public AdminMerchantView suspend(UUID adminId, UUID merchantId, String reason) {
        return decide(adminId, merchantId, "MERCHANT_SUSPENDED", reason,
                (merchant, now) -> merchant.suspend(adminId, reason, now),
                identity::revokeMerchantRole, TemplateKey.MERCHANT_SUSPENDED);
    }

    /**
     * One transaction: the status change (only if nobody changed it meanwhile), the role change
     * and the audit entry are saved together or not at all. The email is sent after the commit.
     */
    private AdminMerchantView decide(UUID adminId, UUID merchantId, String auditAction, String reason,
                                     BiFunction<Merchant, Instant, Merchant> transition,
                                     Consumer<UUID> roleChange, TemplateKey template) {
        Instant now = clock.instant();
        Merchant updated = tx.execute(status -> {
            Merchant current = merchants.findById(merchantId).orElseThrow(MerchantNotFoundException::new);
            Merchant next = transition.apply(current, now);
            if (!merchants.updateIfStatus(next, current.status())) {
                throw new InvalidMerchantStateException("The merchant was changed by someone else. Reload and try again.");
            }
            roleChange.accept(next.ownerUserId());
            audit.append(adminId, auditAction, "MERCHANT", merchantId.toString(), next.decisionReason(), "SUCCESS");
            return next;
        });

        identity.emailOf(updated.ownerUserId()).ifPresent(email -> {
            Map<String, String> params = updated.decisionReason() == null
                    ? Map.of("merchantName", updated.name())
                    : Map.of("merchantName", updated.name(), "reason", updated.decisionReason());
            notifications.sendEmail(email, template, params);
        });
        return toAdminView(updated);
    }

    private AdminMerchantView toAdminView(Merchant merchant) {
        return new AdminMerchantView(merchant, identity.emailOf(merchant.ownerUserId()).orElse(null));
    }
}
