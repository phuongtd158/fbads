package com.fbads.service.facebook;

import com.fbads.service.facebook.FacebookState.AccInfo;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

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
        JsonNode r = graph.call("GET", actOf(id), Map.of("fields", "name,currency,account_status"), null);
        String name = r.path("name").asString("");
        int st = r.path("account_status").asInt(0);
        state.ws().accInfo.put(id, new AccInfo(name, r.path("currency").asString(""), st, System.currentTimeMillis()));
        return new AccountHealth(id, name.isEmpty() ? id : name, st, ACC_STATUS.getOrDefault(st, "Trạng thái " + st),
                st == 1);
    }

    /** Quảng cáo đang bị từ chối của một tài khoản, kèm camp/nhóm QC chứa nó và lý do (nếu Facebook cho biết) */
    public List<DisapprovedAd> disapprovedAds(String id) {
        List<DisapprovedAd> out = new ArrayList<>();
        Map<String, String> p = Map.of("fields", "id,name,campaign{name},adset{name},ad_review_feedback",
                "effective_status", "[\"DISAPPROVED\"]");
        for (JsonNode a : graph.callAll(actOf(id) + "/ads", p, null)) {
            List<String> reasons = new ArrayList<>();
            for (JsonNode v : a.path("ad_review_feedback").path("global").values()) {
                String t = v.asString("");
                if (!t.isEmpty()) reasons.add(t);
            }
            String reason = String.join("; ", reasons);
            out.add(new DisapprovedAd(a.path("id").asString(""), a.path("name").asString(""),
                    a.path("campaign").path("name").asString(""), a.path("adset").path("name").asString(""),
                    reason.length() > 300 ? reason.substring(0, 300) : reason));
        }
        return out;
    }
}
