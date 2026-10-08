package com.fbads.service.facebook;

import com.fbads.client.FbException;
import com.fbads.client.GraphClient;
import com.fbads.config.CacheConfig;
import com.fbads.dto.Responses.Connection;
import com.fbads.dto.Responses.FbAccount;
import com.fbads.dto.Responses.FbAccounts;
import com.fbads.dto.Responses.TokenStatus;
import com.fbads.service.facebook.FacebookState.AccInfo;
import com.fbads.service.facebook.GraphData.AccessToken;
import com.fbads.service.facebook.GraphData.Account;
import com.fbads.service.facebook.GraphData.Me;
import com.fbads.service.facebook.GraphData.TokenDebug;
import com.fbads.service.facebook.GraphData.TokenInfo;
import com.fbads.settings.AppSettings;
import com.fbads.settings.SettingsService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

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
    public TokenStatus inspectToken(String token) {
        TokenDebug r = graph.get("debug_token", Map.of("input_token", token), token, TokenDebug.class);
        TokenInfo d = r.data() != null ? r.data() : new TokenInfo(null, null, null, null, null);
        List<String> scopes = d.scopes();
        long exp = d.expiresAtMs();
        return new TokenStatus(d.valid(), exp == 0 ? null : exp,
                exp == 0 ? null : Math.floorDiv(exp - System.currentTimeMillis(), 86_400_000L), scopes,
                NEED_SCOPES.stream().filter(x -> !scopes.contains(x)).toList(), d.type(), d.appId());
    }

    private TokenStatus tryInspect(String token) {
        try {
            return inspectToken(token);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Danh sách tài khoản của một token: giữ 5 phút ở Redis. Khoá cache là mã băm của token, không phải token. */
    @Cacheable(cacheNames = CacheConfig.ACCOUNTS, key = "T(com.fbads.common.Hash).sha256(#token)")
    public FbAccounts listAccounts(String token) {
        Me me = graph.get("me", Map.of("fields", "name"), token, Me.class);
        List<Account> list = graph.getAll("me/adaccounts", Map.of("fields", "account_id,name,currency,account_status"),
                token, Account.class);
        List<FbAccount> accs = list.stream()
                .map(a -> new FbAccount(a.accountId(), a.name(), a.currency(), statusText(a.status()), a.status() == 1, null))
                .toList();
        return new FbAccounts(me.name(), tryInspect(token), accs);
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
    public Connection testConnection() {
        if (state.isMock()) return Connection.demo();
        AppSettings s = settings.get();
        List<String> ids = s.accountIds();
        if (s.getAccessToken().isEmpty()) throw new FbException("Chưa có Access Token.", null);
        if (ids.isEmpty()) throw new FbException("Chưa chọn tài khoản quảng cáo.", null);
        Me me = graph.get("me", Map.of("fields", "name"), null, Me.class);
        TokenStatus tk = tryInspect(s.getAccessToken());
        List<FbAccount> accounts = ids.stream().map(this::checkAccount).toList();
        List<FbAccount> ok = accounts.stream().filter(a -> a.error() == null).toList();
        if (ok.isEmpty()) throw new FbException(accounts.getFirst().error(), null);
        state.ws().currency = ok.getFirst().currency();
        int n = accounts.size();
        long activeN = accounts.stream().filter(FbAccount::active).count();
        String name = n > 1 ? n + " tài khoản quảng cáo" : accounts.getFirst().name();
        String currency = String.join(", ", new LinkedHashSet<>(ok.stream().map(FbAccount::currency).toList()));
        String status = n > 1 ? activeN + "/" + n + " đang hoạt động" : accounts.getFirst().status();
        return new Connection(true, null, me.name(), accounts, name, currency, status, activeN == n, tk,
                System.currentTimeMillis(), null);
    }

    /** Một tài khoản cho testConnection: lỗi thì ghi vào "error" (trừ khi bị giới hạn số lần gọi) */
    private FbAccount checkAccount(String id) {
        try {
            Account r = graph.get(actOf(id), Map.of("fields", "name,currency,account_status"), null, Account.class);
            int st = r.status();
            state.ws().accInfo.put(id, new AccInfo(r.name(), r.currency(), st, System.currentTimeMillis()));
            return new FbAccount(id, r.name(), r.currency(), statusText(st), st == 1, null);
        } catch (FbException e) {
            if (e.isRateLimit()) throw e;
            AccInfo ai = state.ws().accInfo.get(id);
            return new FbAccount(id, ai != null ? ai.name() : id, null, "Lỗi", false, e.getMessage());
        }
    }

    /** Trạng thái tài khoản bằng chữ (1 = Đang hoạt động…); mã lạ thì ghi số */
    private static String statusText(int status) { return ACC_STATUS.getOrDefault(status, String.valueOf(status)); }
}
