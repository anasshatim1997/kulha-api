package ma.kulha.admin.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Admin-owned reporting projection for support cases linked to offers. */
public interface AdminSupportCaseReportingApi {
    Map<UUID, Long> openCaseCountsByOfferIds(Collection<UUID> offerIds);
}
