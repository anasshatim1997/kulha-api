package ma.kulha.merchant.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MerchantPublicApi {
    List<UUID> findApprovedMerchantIds(String city, String district);

    List<UUID> findApprovedMerchantIdsByName(String query, String city, String district);

    List<MerchantPublicProfile> findApprovedPublicProfiles(Collection<UUID> merchantIds);
}
