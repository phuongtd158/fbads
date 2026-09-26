package com.fbads.web;

import com.fbads.config.AppProperties;
import com.fbads.facebook.FacebookService;
import com.fbads.settings.AppSettings;
import com.fbads.settings.SettingsService;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

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
     * Callback vì vậy không đòi đăng nhập mà dựa vào `state`: mã ngẫu nhiên dùng 1 lần, chỉ người đã đăng nhập mới tạo được, sống 10 phút.
     */
    private record Pending(String appId, String redirectUri, long exp) {}
    private final Map<String, Pending> oauthStates = new ConcurrentHashMap<>();
    private volatile String oauthError = "";

    private final FacebookService fb;
    private final SettingsService settings;
    private final AppProperties props;

    public FacebookController(FacebookService fb, SettingsService settings, AppProperties props) {
        this.fb = fb;
        this.settings = settings;
        this.props = props;
    }

    private static String str(JsonNode b, String k) {
        JsonNode v = b == null ? null : b.get(k);
        return v == null || v.isNull() ? "" : v.asString("").trim();
    }

    private static JsonNode body(JsonNode b) { return b == null || !b.isObject() ? JsonNodeFactory.instance.objectNode() : b; }

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
        if (oauthStates.size() > 50) oauthStates.clear();
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        String st = HexFormat.of().formatHex(b);
        oauthStates.put(st, new Pending(appId, uri, now + 10 * 60_000));
        return st;
    }

    /** Không cần đăng nhập (permitAll trong SecurityConfig) */
    @GetMapping("/fb/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
                                  @RequestParam(required = false) String error) {
        Pending pending = oauthStates.remove(state == null ? "" : state);
        if (pending == null || pending.exp() < System.currentTimeMillis()) return back("expired");
        if (error != null || code == null || code.isEmpty()) return back("cancel"); // người dùng bấm Huỷ trên Facebook
        try {
            String token = fb.exchangeCode(pending.appId(), settings.get().getFbAppSecret(), pending.redirectUri(), code);
            settings.update(s -> s.setAccessToken(token));
            fb.resetCache();
            oauthError = "";
            return back("ok");
        } catch (RuntimeException e) {
            oauthError = e.getMessage();
            return back("fail");
        }
    }

    /** Chỉ đưa mã kết quả lên URL; nội dung lỗi lấy qua /api/fb/oauth (tránh người ngoài chèn chữ tuỳ ý vào giao diện) */
    private static ResponseEntity<Void> back(String result) {
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, OAUTH_BACK + result).build();
    }

    @RequestMapping(value = {"/test-connection", "/connection"}, method = {RequestMethod.GET, RequestMethod.POST})
    Map<String, Object> testConnection() {
        try {
            return fb.testConnection();
        } catch (RuntimeException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", e.getMessage());
            return m;
        }
    }

    /** Liệt kê tài khoản quảng cáo từ token (token mới dán chưa cần lưu) */
    @PostMapping("/fb/accounts")
    ResponseEntity<?> accounts(@RequestBody(required = false) JsonNode b) {
        String token = str(b, "token");
        if (token.isEmpty()) token = settings.get().getAccessToken();
        String tm = Checks.checkToken(token);
        if (!tm.isEmpty()) return ApiExceptionHandler.error(400, tm);
        return ResponseEntity.ok(fb.listAccounts(token));
    }

    /** Đổi token ngắn hạn thành ~60 ngày. App ID/Secret chỉ dùng 1 lần, không lưu. */
    @PostMapping("/fb/extend")
    ResponseEntity<?> extend(@RequestBody(required = false) JsonNode b) {
        String token = str(b, "token");
        if (token.isEmpty()) token = settings.get().getAccessToken();
        String appId = str(b, "appId"), appSecret = str(b, "appSecret");
        String em = firstError(Checks.checkToken(token), Checks.checkAppId(appId), Checks.checkAppSecret(appSecret));
        if (!em.isEmpty()) return ApiExceptionHandler.error(400, em);
        String longToken = fb.extendToken(appId, appSecret, token);
        settings.update(s -> s.setAccessToken(longToken));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("token", fb.inspectToken(longToken));
        return ResponseEntity.ok(m);
    }

    /** Địa chỉ cần khai báo trong ứng dụng Meta + lỗi của lần đăng nhập Facebook gần nhất */
    @GetMapping("/fb/oauth")
    Map<String, Object> oauth(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("redirectUri", redirectUri(req));
        m.put("error", oauthError);
        return m;
    }

    /** Bắt đầu đăng nhập bằng Facebook: lưu App ID/Secret (để lần sau chỉ cần bấm 1 nút) rồi trả về địa chỉ trang đăng nhập */
    @PostMapping("/fb/oauth/start")
    ResponseEntity<?> oauthStart(@RequestBody(required = false) JsonNode raw, HttpServletRequest req) {
        JsonNode b = body(raw);
        AppSettings s = settings.get();
        String appId = str(b, "appId");
        if (appId.isEmpty()) appId = s.getFbAppId() == null ? "" : s.getFbAppId();
        String appSecret = str(b, "appSecret");
        if (appSecret.isEmpty()) appSecret = appId.equals(s.getFbAppId()) && s.getFbAppSecret() != null ? s.getFbAppSecret() : ""; // đổi ứng dụng thì phải nhập secret mới
        String configId = b.has("configId") ? str(b, "configId") : (s.getFbConfigId() == null ? "" : s.getFbConfigId());
        String em = firstError(Checks.checkAppId(appId), appSecret.isEmpty() ? "Hãy nhập App Secret" : Checks.checkAppSecret(appSecret), Checks.checkConfigId(configId));
        if (!em.isEmpty()) return ApiExceptionHandler.error(400, em);
        String fAppId = appId, fSecret = appSecret, fConfig = configId;
        settings.update(x -> { x.setFbAppId(fAppId); x.setFbAppSecret(fSecret); x.setFbConfigId(fConfig); });
        oauthError = "";
        String uri = redirectUri(req);
        return ResponseEntity.ok(Map.of("url", fb.oauthUrl(appId, configId, uri, newOauthState(appId, uri))));
    }
}
