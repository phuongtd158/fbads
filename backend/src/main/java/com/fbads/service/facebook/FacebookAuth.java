package com.fbads.service.facebook;

import com.fbads.client.FbException;
import com.fbads.client.GraphClient;
import com.fbads.config.CacheConfig;
import com.fbads.entity.AppSettings;
import com.fbads.service.SettingsService;
import com.fbads.service.facebook.FacebookState.AccInfo;
import com.fbads.service.facebook.GraphData.AccessToken;
import com.fbads.service.facebook.GraphData.Account;
import com.fbads.service.facebook.GraphData.Me;
import com.fbads.service.facebook.GraphData.TokenDebug;
import com.fbads.service.facebook.GraphData.TokenInfo;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.fbads.service.facebook.FacebookGraph.actOf;
import static com.fbads.service.facebook.FacebookParse.ACC_STATUS;

/** Token và kết nối: kiểm tra token, đăng nhập bằng Facebook (OAuth), gia hạn token, tài khoản của token, kiểm tra kết nối. */
@Service
public class FacebookAuth {
    public static final List<String> NEED_SCOPES = List.of("ads_management", "ads_read");

    private final SettingsService settings;
    private final FacebookGraph graph;
    private final FacebookState state;

    public FacebookAuth(SettingsService settings, FacebookGraph graph, FacebookState state) {
        this.settings = settings;
        this.graph = graph;
        this.state = state;
    }

    /** Hỏi Facebook về token: còn hợp lệ không, hết hạn khi nào, có đủ quyền không */
    public Map<String, Object> inspectToken(String token) {
        TokenDebug r = graph.get("debug_token", Map.of("input_token", token), token, TokenDebug.class);
        TokenInfo d = r.data() != null ? r.data() : new TokenInfo(null, null, null, null, null);
        List<String> scopes = d.scopes();
        long exp = d.expiresAtMs();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", d.valid());
        m.put("expiresAt", exp == 0 ? null : exp);
        m.put("daysLeft", exp == 0 ? null : Math.floorDiv(exp - System.currentTimeMillis(), 86_400_000L));
        m.put("scopes", scopes);
        m.put("missing", NEED_SCOPES.stream().filter(x -> !scopes.contains(x)).toList());
        m.put("type", d.type());
        m.put("appId", d.appId());
        return m;
    }

    private Map<String, Object> tryInspect(String token) {
        try {
            return inspectToken(token);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Danh sách tài khoản của một token: giữ 5 phút ở Redis. Khoá cache là mã băm của token, không phải token. */
    @Cacheable(cacheNames = CacheConfig.ACCOUNTS, key = "T(com.fbads.common.Hash).sha256(#token)")
    public Map<String, Object> listAccounts(String token) {
        Me me = graph.get("me", Map.of("fields", "name"), token, Me.class);
        List<Account> list = graph.getAll("me/adaccounts", Map.of("fields", "account_id,name,currency,account_status"),
                token, Account.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", me.name());
        out.put("token", tryInspect(token));
        List<Map<String, Object>> accs = new ArrayList<>();
        for (Account a : list) {
            int st = a.status();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.accountId());
            m.put("name", a.name());
            m.put("currency", a.currency());
            m.put("status", ACC_STATUS.getOrDefault(st, String.valueOf(st)));
            m.put("active", st == 1);
            accs.add(m);
        }
        out.put("accounts", accs);
        return out;
    }

    /** Đổi token ngắn hạn (vài giờ) thành token dài hạn (~60 ngày) */
    public String extendToken(String appId, String appSecret, String token) {
        Map<String, String> p = Map.of("grant_type", "fb_exchange_token", "client_id", appId, "client_secret",
                appSecret, "fb_exchange_token", token);
        return graph.get("oauth/access_token", p, token, AccessToken.class).accessToken();
    }

    /** Trang đăng nhập của Facebook; sau khi cho phép, Facebook chuyển về redirectUri kèm ?code=…&state=… */
    public String oauthUrl(String appId, String configId, String redirectUri, String state) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("client_id", appId);
        p.put("redirect_uri", redirectUri);
        p.put("state", state);
        p.put("response_type", "code");
        if (configId != null && !configId.isEmpty()) {
            p.put("config_id", configId);
        } else {
            p.put("scope", String.join(",", NEED_SCOPES));
            p.put("auth_type", "rerequest");
        }
        return "https://www.facebook.com/" + settings.get().getApiVersion() + "/dialog/oauth?" + GraphClient.form(p);
    }

    /** Đổi code lấy token rồi gia hạn lên ~60 ngày (gia hạn lỗi thì vẫn dùng token ngắn hạn) */
    public String exchangeCode(String appId, String appSecret, String redirectUri, String code) {
        String appToken = appId + "|" + appSecret;
        String t = graph.get("oauth/access_token",
                Map.of("client_id", appId, "client_secret", appSecret, "redirect_uri", redirectUri, "code", code),
                appToken, AccessToken.class).accessToken();
        if (t == null) throw new FbException("Facebook không trả về token.", null);
        try {
            String longT = extendToken(appId, appSecret, t);
            return longT != null ? longT : t;
        } catch (RuntimeException e) {
            return t;
        }
    }

    /** Trang Kết nối: token còn dùng được không, từng tài khoản quảng cáo có hoạt động không */
    public Map<String, Object> testConnection() {
        if (state.isMock()) {
            return new LinkedHashMap<>(Map.of("ok", true, "mock", true, "name", "Chế độ dùng thử (dữ liệu giả)",
                    "currency", "VND"));
        }
        AppSettings s = settings.get();
        List<String> ids = s.accountIds();
        if (s.getAccessToken().isEmpty()) throw new FbException("Chưa có Access Token.", null);
        if (ids.isEmpty()) throw new FbException("Chưa chọn tài khoản quảng cáo.", null);
        Me me = graph.get("me", Map.of("fields", "name"), null, Me.class);
        Map<String, Object> tk = tryInspect(s.getAccessToken());
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (String id : ids) accounts.add(checkAccount(id));
        List<Map<String, Object>> ok = accounts.stream().filter(a -> !a.containsKey("error")).toList();
        if (ok.isEmpty()) throw new FbException((String) accounts.getFirst().get("error"), null);
        state.ws().currency = (String) ok.getFirst().get("currency");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("user", me.name());
        out.put("accounts", accounts);
        out.put("name", accounts.size() > 1 ? accounts.size() + " tài khoản quảng cáo" : accounts.getFirst().get("name"));
        out.put("currency", String.join(", ", new LinkedHashSet<>(ok.stream().map(a -> (String) a.get("currency"))
                .toList())));
        long activeN = accounts.stream().filter(a -> Boolean.TRUE.equals(a.get("active"))).count();
        out.put("status", accounts.size() > 1 ? activeN + "/" + accounts.size() + " đang hoạt động"
                : accounts.getFirst().get("status"));
        out.put("accountActive", activeN == accounts.size());
        out.put("token", tk);
        out.put("checkedAt", System.currentTimeMillis());
        return out;
    }

    /** Một tài khoản cho testConnection: lỗi thì ghi vào "error" (trừ khi bị giới hạn số lần gọi) */
    private Map<String, Object> checkAccount(String id) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", id);
        try {
            Account r = graph.get(actOf(id), Map.of("fields", "name,currency,account_status"), null, Account.class);
            int st = r.status();
            state.ws().accInfo.put(id, new AccInfo(r.name(), r.currency(), st, System.currentTimeMillis()));
            a.put("name", r.name());
            a.put("currency", r.currency());
            a.put("status", ACC_STATUS.getOrDefault(st, String.valueOf(st)));
            a.put("active", st == 1);
        } catch (FbException e) {
            if (e.isRateLimit()) throw e;
            AccInfo ai = state.ws().accInfo.get(id);
            a.put("name", ai != null ? ai.name() : id);
            a.put("status", "Lỗi");
            a.put("active", false);
            a.put("error", e.getMessage());
        }
        return a;
    }
}
