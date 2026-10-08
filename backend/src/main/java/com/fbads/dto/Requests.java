package com.fbads.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fbads.common.JsNumber;
import com.fbads.validation.StrongPassword;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Body của các request đơn giản. Jackson đọc JSON thẳng vào record; gửi sai kiểu (vd. "on": "abc")
 * thì ApiExceptionHandler trả 400 { error, errors: { trường: "Sai kiểu dữ liệu" } }.
 * Trường chữ không gửi = "" (constructor gọn bên dưới), như `req.body.x || ''` của bản Node.
 * Body bỏ trống: controller dùng hằng EMPTY của từng record, giống gửi {}.
 * Luật kiểm tra nằm ở chú thích Bean Validation (controller dùng @Valid) hoặc ở service.
 */
public final class Requests {
    private Requests() {}

    static String text(String s) { return s == null ? "" : s; }

    static String trimmed(String s) { return s == null ? "" : s.trim(); }

    /** Đổi mật khẩu đăng nhập */
    public record PasswordChange(String currentPassword, @StrongPassword String newPassword) {}

    /** Đăng nhập. Bỏ trống username = "admin" (giao diện cũ chỉ gửi mật khẩu). */
    public record Login(String username, String password) {
        public static final Login EMPTY = new Login(null, null);

        public Login { username = text(username); password = text(password); }
    }

    /** Tạo tài khoản đầu tiên (/setup) hoặc tự đăng ký (/register, thêm tên workspace) */
    public record Signup(String username, String name, String password, String workspaceName) {
        public static final Signup EMPTY = new Signup(null, null, null, null);

        public Signup { username = text(username); name = text(name); password = text(password); workspaceName = text(workspaceName); }
    }

    /** Chọn workspace làm việc */
    public record WorkspaceSwitch(Long id) {
        public static final WorkspaceSwitch EMPTY = new WorkspaceSwitch(null);

        public WorkspaceSwitch { id = id == null ? 0L : id; }
    }

    /** Tạo hoặc đổi tên workspace */
    public record WorkspaceName(String name) {
        public static final WorkspaceName EMPTY = new WorkspaceName(null);

        public WorkspaceName { name = text(name); }
    }

    /** Thêm thành viên: password chỉ cần khi tạo tài khoản mới. role sai thì Role.parse trả null, service báo lỗi. */
    public record MemberAdd(String username, String name, String password, String role) {
        public static final MemberAdd EMPTY = new MemberAdd(null, null, null, null);

        public MemberAdd { username = text(username); name = text(name); password = text(password); role = text(role); }
    }

    /** Đổi vai trò thành viên */
    public record RoleChange(String role) {
        public static final RoleChange EMPTY = new RoleChange(null);

        public RoleChange { role = text(role); }
    }

    /** Token Facebook (bỏ trống = token đã lưu) + App ID/Secret khi gia hạn token */
    public record FbToken(String token, String appId, String appSecret) {
        public static final FbToken EMPTY = new FbToken(null, null, null);

        public FbToken { token = trimmed(token); appId = trimmed(appId); appSecret = trimmed(appSecret); }
    }

    /**
     * Bắt đầu đăng nhập bằng Facebook. appId/appSecret bỏ trống = dùng cái đã lưu.
     * configId: null = không gửi (dùng cái đã lưu), "" = bỏ Configuration ID.
     */
    public record OauthStart(String appId, String appSecret, String configId) {
        public static final OauthStart EMPTY = new OauthStart(null, null, null);

        public OauthStart { appId = trimmed(appId); appSecret = trimmed(appSecret); configId = configId == null ? null : configId.trim(); }
    }

    /** POST /api/company/reports/build: tạo bản báo cáo của một mốc ngay; notify = nhắn Telegram như đến mốc */
    public record CompanyBuild(@JsNumber Double slot,
            @JsonProperty("notify") Boolean telegram) {
        public static final CompanyBuild EMPTY = new CompanyBuild(null, null);
    }

    /** POST /api/company/reports/{id}/update: cập nhật báo cáo đã có trên công ty, lý do bắt buộc */
    public record CompanyUpdate(String reason) {
        public static final CompanyUpdate EMPTY = new CompanyUpdate(null);
    }

    /**
     * POST /api/notify/channels (thêm) và /api/notify/channels/{id} (sửa): một kênh thông báo.
     * config để dạng JSON vì mỗi loại kênh có các ô khác nhau (Telegram: token + chatId; Gmail: tài khoản + mật khẩu…);
     * lớp kênh (NotifyChannel.validate) kiểm tra từng ô.
     *
     * @param type   loại kênh ("telegram"…); chỉ dùng khi thêm, sửa thì giữ loại cũ
     * @param topics tên các Notice.Topic kênh nhận; null = mọi loại tin
     */
    public record NotifyTargetSave(String type, String name, Boolean enabled, List<String> topics, ObjectNode config) {
        public static final NotifyTargetSave EMPTY = new NotifyTargetSave(null, null, null, null, null);
    }
}
