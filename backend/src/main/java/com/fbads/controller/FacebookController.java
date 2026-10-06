package com.fbads.controller;

import com.fbads.config.AppProperties;
import com.fbads.dto.Requests;
import com.fbads.entity.AppSettings;
import com.fbads.security.WorkspaceContext;
import com.fbads.service.SettingsService;
import com.fbads.service.facebook.FacebookAuth;
import com.fbads.service.facebook.FacebookState;
import com.fbads.validation.Checks;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Kết nối Facebook: thử kết nối, liệt kê tài khoản, gia hạn token, đăng nhập bằng Facebook (OAuth). */
@RestController
@RequestMapping("/api")
public class FacebookController {
    private static final String OAUTH_BACK = "/#/settings/connection?fbLogin=";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Cookie phiên là SameSite=Strict nên khi Facebook chuyển về /api/fb/callback trình duyệt KHÔNG gửi cookie.
     * Callback vì vậy không đòi đăng nhập mà dựa vào `state`: mã ngẫu nhiên dùng 1 lần, chỉ chủ workspace mới tạo được, sống 10 phút.
     * `state` nhớ luôn workspace đã bắt đầu đăng nhập: token nhận về được lưu vào đúng workspace đó.
     */
    private record Pending(long workspaceId, String appId, String redirectUri, long exp) {}
    private final Map<String, Pending> oauthStates = new ConcurrentHashMap<>();
    /** Lỗi của lần đăng nhập Facebook gần nhất, theo workspace */
    private final Map<Long, String> oauthErrors = new ConcurrentHashMap<>();

    private final FacebookAuth auth;
    private final FacebookState fbState;
    private final SettingsService settings;
    private final AppProperties props;

    public FacebookController(FacebookAuth auth, FacebookState fbState, SettingsService settings, AppProperties props) {
        this.auth = auth;
        this.fbState = fbState;
        this.settings = settings;
        this.props = props;
    }

    private static String firstError(String... msgs) {
        for (String m : msgs) if (m != null && !m.isEmpty()) return m;
        return "";
    }

    String redirectUri(HttpServletRequest req) {
        String pub = props.publicUrl();
        if (pub != null && !pub.isBlank()) return pub.replaceAll("/+$", "") + "/api/fb/callback";
        // scheme đã tính cả X-Forwarded-Proto nhờ server.forward-headers-strategy=native
        return req.getScheme() + "://" + req.getHeader(HttpHeaders.HOST) + "/api/fb/callback";
    }

    private String newOauthState(String appId, String uri) {
        long now = System.currentTimeMillis();
        oauthStates.values().removeIf(p -> p.exp() < now);
        if (oauthStates.size() > 500) oauthStates.clear();
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        String st = HexFormat.of().formatHex(b);
        oauthStates.put(st, new Pending(WorkspaceContext.require(), appId, uri, now + 10 * 60_000));
        return st;
    }

    /** Không cần đăng nhập (permitAll trong SecurityConfig) */
    @GetMapping("/fb/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
                                  @RequestParam(required = false) String error) {
        Pending pending = oauthStates.remove(state == null ? "" : state);
        if (pending == null || pending.exp() < System.currentTimeMillis()) return back("expired");
        if (error != null || code == null || code.isEmpty()) return back("cancel"); // người dùng bấm Huỷ trên Facebook
        return WorkspaceContext.call(pending.workspaceId(), () -> {
            try {
                String token = auth.exchangeCode(pending.appId(), settings.get().getFbAppSecret(), pending.redirectUri(), code);
                settings.update(s -> s.setAccessToken(token));
                fbState.resetCache();
                oauthErrors.remove(pending.workspaceId());
                return back("ok");
            } catch (RuntimeException e) {
                oauthErrors.put(pending.workspaceId(), String.valueOf(e.getMessage()));
                return back("fail");
            }
        });
    }

    /** Chỉ đưa mã kết quả lên URL; nội dung lỗi lấy qua /api/fb/oauth (tránh người ngoài chèn chữ tuỳ ý vào giao diện) */
    private static ResponseEntity<Void> back(String result) {
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, OAUTH_BACK + result).build();
    }

    @RequestMapping(value = {"/test-connection", "/connection"}, method = {RequestMethod.GET, RequestMethod.POST})
    Map<String, Object> testConnection() {
        try {
            return auth.testConnection();
        } catch (RuntimeException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", e.getMessage());
            return m;
        }
    }

    /** Liệt kê tài khoản quảng cáo từ token (token mới dán chưa cần lưu) */
    @PostMapping("/fb/accounts")
    ResponseEntity<?> accounts(@RequestBody(required = false) Requests.FbToken b) {
        String token = (b == null ? Requests.FbToken.EMPTY : b).token();
        if (token.isEmpty()) token = settings.get().getAccessToken();
        String tm = Checks.checkToken(token);
        if (!tm.isEmpty()) return ApiExceptionHandler.error(400, tm);
        return ResponseEntity.ok(auth.listAccounts(token));
    }

    /** Đổi token ngắn hạn thành ~60 ngày. App ID/Secret chỉ dùng 1 lần, không lưu. */
    @PostMapping("/fb/extend")
    ResponseEntity<?> extend(@RequestBody(required = false) Requests.FbToken body) {
        Requests.FbToken b = body == null ? Requests.FbToken.EMPTY : body;
        String token = b.token();
        if (token.isEmpty()) token = settings.get().getAccessToken();
        String appId = b.appId(), appSecret = b.appSecret();
        String em = firstError(Checks.checkToken(token), Checks.checkAppId(appId), Checks.checkAppSecret(appSecret));
        if (!em.isEmpty()) return ApiExceptionHandler.error(400, em);
        String longToken = auth.extendToken(appId, appSecret, token);
        settings.update(s -> s.setAccessToken(longToken));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("token", auth.inspectToken(longToken));
        return ResponseEntity.ok(m);
    }

    /** Địa chỉ cần khai báo trong ứng dụng Meta + lỗi của lần đăng nhập Facebook gần nhất */
    @GetMapping("/fb/oauth")
    Map<String, Object> oauth(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("redirectUri", redirectUri(req));
        m.put("error", oauthErrors.getOrDefault(WorkspaceContext.require(), ""));
        return m;
    }

    /** Bắt đầu đăng nhập bằng Facebook: lưu App ID/Secret (để lần sau chỉ cần bấm 1 nút) rồi trả về địa chỉ trang đăng nhập */
    @PostMapping("/fb/oauth/start")
    ResponseEntity<?> oauthStart(@RequestBody(required = false) Requests.OauthStart body, HttpServletRequest req) {
        Requests.OauthStart b = body == null ? Requests.OauthStart.EMPTY : body;
        AppSettings s = settings.get();
        String appId = b.appId();
        if (appId.isEmpty()) appId = s.getFbAppId() == null ? "" : s.getFbAppId();
        String appSecret = b.appSecret();
        // đổi ứng dụng thì phải nhập secret mới
        if (appSecret.isEmpty()) appSecret = appId.equals(s.getFbAppId()) && s.getFbAppSecret() != null ? s.getFbAppSecret() : "";
        String configId = b.configId() != null ? b.configId() : (s.getFbConfigId() == null ? "" : s.getFbConfigId());
        String em = firstError(Checks.checkAppId(appId),
                appSecret.isEmpty() ? "Hãy nhập App Secret" : Checks.checkAppSecret(appSecret), Checks.checkConfigId(configId));
        if (!em.isEmpty()) return ApiExceptionHandler.error(400, em);
        String fAppId = appId, fSecret = appSecret, fConfig = configId;
        settings.update(x -> { x.setFbAppId(fAppId); x.setFbAppSecret(fSecret); x.setFbConfigId(fConfig); });
        oauthErrors.remove(WorkspaceContext.require());
        String uri = redirectUri(req);
        return ResponseEntity.ok(Map.of("url", auth.oauthUrl(appId, configId, uri, newOauthState(appId, uri))));
    }
}
