package com.fbads.ads;

import com.fbads.ads.ObjectsMeta.AccountError;
import com.fbads.ads.ObjectsMeta.AccountRef;
import com.fbads.ads.ObjectsMeta.Usage;

import java.util.List;

/** GET /api/objects: camp + nhóm QC kèm số hôm nay, và các trường của ObjectsMeta */
public record ObjectsList(List<AdObject> items, Long at, boolean stale, Long blockedUntil, Usage usage,
        List<AccountRef> accounts, List<AccountError> accountErrors) {
    public ObjectsList(List<AdObject> items, ObjectsMeta m) {
        this(items, m.at(), m.stale(), m.blockedUntil(), m.usage(), m.accounts(), m.accountErrors());
    }
}
