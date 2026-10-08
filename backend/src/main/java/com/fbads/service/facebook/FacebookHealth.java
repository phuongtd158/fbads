package com.fbads.service.facebook;

import com.fbads.service.facebook.FacebookState.AccInfo;
import com.fbads.service.facebook.GraphData.Account;
import com.fbads.service.facebook.GraphData.Ad;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.fbads.service.facebook.FacebookGraph.actOf;
import static com.fbads.service.facebook.FacebookParse.ACC_STATUS;

/** Tình trạng tài khoản quảng cáo và quảng cáo bị từ chối, cho cảnh báo bất thường (engine/AlertWatch). */
@Service
public class FacebookHealth {
    public record AccountHealth(String id, String name, int status, String statusText, boolean active) {}

    public record DisapprovedAd(String id, String name, String campaign, String adset, String reason) {}

    private final FacebookGraph graph;
    private final FacebookState state;

    public FacebookHealth(FacebookGraph graph, FacebookState state) {
        this.graph = graph;
        this.state = state;
    }

    /** Trạng thái tài khoản quảng cáo (hỏi thẳng Facebook, không dùng bộ nhớ đệm 1 giờ) */
    public AccountHealth accountHealth(String id) {
        Account r = graph.get(actOf(id), Map.of("fields", "name,currency,account_status"), null, Account.class);
        String name = r.name();
        int st = r.status();
        state.ws().accInfo.put(id, new AccInfo(name, r.currency(), st, System.currentTimeMillis()));
        return new AccountHealth(id, name.isEmpty() ? id : name, st, ACC_STATUS.getOrDefault(st, "Trạng thái " + st),
                st == 1);
    }

    /** Quảng cáo đang bị từ chối của một tài khoản, kèm camp/nhóm QC chứa nó và lý do (nếu Facebook cho biết) */
    public List<DisapprovedAd> disapprovedAds(String id) {
        List<DisapprovedAd> out = new ArrayList<>();
        Map<String, String> p = Map.of("fields", "id,name,campaign{name},adset{name},ad_review_feedback",
                "effective_status", "[\"DISAPPROVED\"]");
        for (Ad a : graph.getAll(actOf(id) + "/ads", p, null, Ad.class)) {
            String reason = String.join("; ", a.reasons());
            out.add(new DisapprovedAd(a.id(), a.name(), a.campaignName(), a.adsetName(),
                    reason.length() > 300 ? reason.substring(0, 300) : reason));
        }
        return out;
    }
}
