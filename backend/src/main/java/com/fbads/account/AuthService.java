package com.fbads.account;

import com.fbads.common.ApiException;
import com.fbads.config.AppProperties;
import com.fbads.security.Passwords;
import com.fbads.security.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tài khoản đăng nhập, workspace và thành viên.
 *  - Chưa có tài khoản nào: "chế độ mở" như bản 1 người dùng cũ, không cần đăng nhập, mọi thao tác vào workspace 1
 *    (chỉ an toàn khi server nghe trên 127.0.0.1). Giao diện mời tạo tài khoản đầu tiên (setup).
 *  - APP_PASSWORD đặt trên server: luôn có tài khoản "admin" với đúng mật khẩu đó (đổi biến rồi khởi động lại là đổi mật khẩu).
 *  - ALLOW_SIGNUP=true: ai cũng tự đăng ký được, mỗi người đăng ký có workspace riêng.
 */
@Service
public class AuthService {
    public static final String ADMIN = "admin";
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._@+-]{3,190}$");
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Một workspace mà người dùng thuộc về */
    public record Membership(long id, String name, Role role) {}

    /** Một thành viên của workspace (gửi về giao diện) */
    public record Member(long userId, String username, String name, Role role) {}

    private final AppProperties props;
    private final UserRepository users;
    private final WorkspaceRepository workspaces;
    private final MemberRepository members;
    private final PasswordEncoder encoder = Passwords.encoder();
    /** Đã có tài khoản nào chưa. Chỉ đổi từ false sang true (không có chức năng xoá tài khoản) nên giữ trong bộ nhớ được. */
    private volatile boolean hasUsers;

    public AuthService(AppProperties props, UserRepository users, WorkspaceRepository workspaces, MemberRepository members) {
        this.props = props;
        this.users = users;
        this.workspaces = workspaces;
        this.members = members;
    }

    // ------------------------------------------------------------------ Trạng thái
    /** Chưa có tài khoản nào: không cần đăng nhập */
    public boolean openMode() {
        if (hasUsers) return false;
        hasUsers = users.count() > 0;
        return !hasUsers;
    }

    /** Đọc lại "đã có tài khoản chưa" từ DB (sau khi xoá tài khoản thẳng trong DB, vd. trong kiểm thử) */
    public void recheckUsers() { hasUsers = false; }

    /** Có bắt đăng nhập không (= đã có ít nhất một tài khoản) */
    public boolean enabled() { return !openMode(); }

    /** Mật khẩu của "admin" do biến môi trường APP_PASSWORD quản lý */
    public boolean envManaged() { return !props.appPassword().isEmpty(); }

    public boolean signupAllowed() { return props.allowSignup(); }

    public static boolean isAuthenticated(Authentication a) {
        return a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken);
    }

    /** Được phép gọi API: chế độ mở, hoặc đã đăng nhập */
    public boolean allowed(Authentication a) { return openMode() || isAuthenticated(a); }

    // ------------------------------------------------------------------ Đăng nhập
    public static String normalize(String username) { return username == null ? "" : username.trim().toLowerCase(); }

    /** Đúng tên + mật khẩu → người dùng; sai → rỗng. Mật khẩu cũ (scrypt của bản Node) được đổi sang bcrypt luôn. */
    @Transactional
    public Optional<User> authenticate(String username, String password) {
        Optional<User> u = users.findByUsername(normalize(username));
        if (u.isEmpty() || password == null || !encoder.matches(password, u.get().getPasswordHash())) {
            // tốn thời gian như khi có tài khoản: không lộ tên nào tồn tại
            if (u.isEmpty()) encoder.matches(password == null ? "" : password, DUMMY);
            return Optional.empty();
        }
        if (encoder.upgradeEncoding(u.get().getPasswordHash())) {
            u.get().setPasswordHash(encoder.encode(password));
            users.save(u.get());
        }
        return u;
    }

    private static final String DUMMY = Passwords.encoder().encode("fbads-dummy-password");

    public Optional<User> user(String username) { return users.findByUsername(normalize(username)); }

    public Optional<User> user(long id) { return users.findById(id); }

    /** Các workspace của người dùng, cũ nhất trước */
    public List<Membership> memberships(long userId) {
        List<Membership> out = new ArrayList<>();
        for (WorkspaceMember m : members.findByKeyUserIdOrderByKeyWorkspaceIdAsc(userId))
            workspaces.findById(m.workspaceId()).ifPresent(w -> out.add(new Membership(w.getId(), w.getName(), m.getRole())));
        return out;
    }

    /** Vai trò của người dùng trong workspace; null = không phải thành viên */
    public Role role(long workspaceId, long userId) {
        return members.findById(new WorkspaceMember.Key(workspaceId, userId)).map(WorkspaceMember::getRole).orElse(null);
    }

    public Optional<Workspace> workspace(long id) { return workspaces.findById(id); }

    // ------------------------------------------------------------------ Tạo tài khoản
    private static void checkUsername(String username) {
        if (!USERNAME.matcher(username).matches())
            throw field("username", "Tên đăng nhập 3–190 ký tự, chỉ gồm chữ thường không dấu, số và . _ @ + -");
    }

    private static void checkPassword(String password) {
        String p = StrongPassword.Validator.problem(password);
        if (!p.isEmpty()) throw field("password", p.replace("Mật khẩu mới", "Mật khẩu"));
    }

    private static String cleanName(String name, int max) {
        String n = name == null ? "" : name.trim();
        return n.length() > max ? n.substring(0, max) : n;
    }

    /** Lỗi của một trường: giao diện hiện ngay dưới ô nhập */
    static ApiException field(String name, String message) {
        return new ApiException(400, message).withFieldError(name, message);
    }

    private User newUser(String username, String name, String password) {
        String u = normalize(username);
        checkUsername(u);
        checkPassword(password);
        if (users.findByUsername(u).isPresent()) throw field("username", "Tên đăng nhập này đã có người dùng");
        User saved = users.save(new User(u, cleanName(name, 100), encoder.encode(password)));
        hasUsers = true;
        return saved;
    }

    /** Tài khoản đầu tiên (chế độ mở): làm chủ workspace 1, nơi đang chứa dữ liệu cũ */
    @Transactional
    public synchronized User setup(String username, String name, String password) {
        if (!openMode()) throw new ApiException(409, "Đã có tài khoản. Hãy đăng nhập.");
        User u = newUser(username, name, password);
        members.save(new WorkspaceMember(WorkspaceContext.DEFAULT, u.getId(), Role.OWNER));
        log.info("Đã tạo tài khoản đầu tiên: {}", u.getUsername());
        return u;
    }

    /** Tự đăng ký (ALLOW_SIGNUP=true): tài khoản mới + workspace riêng */
    @Transactional
    public User register(String username, String name, String password, String workspaceName) {
        if (!signupAllowed()) throw new ApiException(403, "Server không cho tự đăng ký. Nhờ chủ workspace thêm bạn vào.");
        User u = newUser(username, name, password);
        createWorkspace(u.getId(), workspaceName == null || workspaceName.isBlank() ? "Workspace của " + u.getUsername() : workspaceName);
        return u;
    }

    @Transactional
    public Workspace createWorkspace(long ownerId, String name) {
        String n = cleanName(name, 100);
        if (n.isEmpty()) throw field("name", "Nhập tên workspace");
        Workspace w = workspaces.save(new Workspace(n));
        members.save(new WorkspaceMember(w.getId(), ownerId, Role.OWNER));
        return w;
    }

    @Transactional
    public Workspace renameWorkspace(long id, String name) {
        String n = cleanName(name, 100);
        if (n.isEmpty()) throw field("name", "Nhập tên workspace");
        Workspace w = workspaces.findById(id).orElseThrow(() -> new ApiException(404, "Không tìm thấy workspace"));
        w.setName(n);
        return workspaces.save(w);
    }

    // ------------------------------------------------------------------ Thành viên
    public List<Member> members(long workspaceId) {
        List<Member> out = new ArrayList<>();
        for (WorkspaceMember m : members.findByKeyWorkspaceId(workspaceId))
            users.findById(m.userId()).ifPresent(u -> out.add(new Member(u.getId(), u.getUsername(), u.getName(), m.getRole())));
        out.sort((a, b) -> a.role() != b.role() ? b.role().compareTo(a.role()) : a.username().compareTo(b.username()));
        return out;
    }

    /**
     * Thêm thành viên: đã có tài khoản thì chỉ thêm vào workspace (không cần mật khẩu);
     * chưa có thì tạo tài khoản với mật khẩu ban đầu do chủ workspace đặt (báo lại cho người đó).
     */
    @Transactional
    public Member addMember(long workspaceId, String username, String name, String password, Role role) {
        if (role == null) throw field("role", "Chọn vai trò");
        Optional<User> existing = users.findByUsername(normalize(username));
        User u;
        if (existing.isPresent()) {
            u = existing.get();
            if (role(workspaceId, u.getId()) != null) throw field("username", "Người này đã là thành viên");
        } else {
            if (password == null || password.isEmpty()) throw field("password", "Chưa có tài khoản tên này: đặt mật khẩu ban đầu để tạo");
            u = newUser(username, name, password);
        }
        members.save(new WorkspaceMember(workspaceId, u.getId(), role));
        return new Member(u.getId(), u.getUsername(), u.getName(), role);
    }

    /** Mỗi workspace luôn còn ít nhất một chủ: không được hạ quyền hay xoá chủ cuối cùng */
    private void keepOwner(long workspaceId, WorkspaceMember m) {
        if (m.getRole() == Role.OWNER && members.countByKeyWorkspaceIdAndRole(workspaceId, Role.OWNER) <= 1)
            throw new ApiException(400, "Workspace cần ít nhất một chủ (OWNER). Hãy cho người khác làm chủ trước.");
    }

    private WorkspaceMember member(long workspaceId, long userId) {
        return members.findById(new WorkspaceMember.Key(workspaceId, userId))
                .orElseThrow(() -> new ApiException(404, "Không tìm thấy thành viên"));
    }

    @Transactional
    public synchronized void setRole(long workspaceId, long userId, Role role) {
        if (role == null) throw field("role", "Chọn vai trò");
        WorkspaceMember m = member(workspaceId, userId);
        if (role != Role.OWNER) keepOwner(workspaceId, m);
        m.setRole(role);
        members.save(m);
    }

    @Transactional
    public synchronized void removeMember(long workspaceId, long userId) {
        WorkspaceMember m = member(workspaceId, userId);
        keepOwner(workspaceId, m);
        members.delete(m);
    }

    // ------------------------------------------------------------------ Mật khẩu
    public boolean isEnvAdmin(User u) { return envManaged() && ADMIN.equals(u.getUsername()); }

    @Transactional
    public void changePassword(long userId, String current, String next) {
        User u = users.findById(userId).orElseThrow(() -> new ApiException(401, "Cần đăng nhập"));
        if (isEnvAdmin(u))
            throw new ApiException(400, "Mật khẩu của admin đang được đặt bằng biến môi trường APP_PASSWORD trên "
                    + "server, không đổi ở đây được.");
        if (current == null || !encoder.matches(current, u.getPasswordHash()))
            throw field("currentPassword", "Mật khẩu hiện tại không đúng.");
        u.setPasswordHash(encoder.encode(next));
        users.save(u);
    }

    // ------------------------------------------------------------------ Khởi động
    /** APP_PASSWORD: tài khoản "admin" luôn có đúng mật khẩu này; tạo mới thì làm chủ workspace 1 */
    @Transactional
    public void syncEnvAdmin() {
        if (!envManaged()) return;
        Optional<User> admin = users.findByUsername(ADMIN);
        if (admin.isPresent()) {
            if (!encoder.matches(props.appPassword(), admin.get().getPasswordHash())) {
                admin.get().setPasswordHash(encoder.encode(props.appPassword()));
                users.save(admin.get());
                log.info("Đã cập nhật mật khẩu tài khoản admin theo APP_PASSWORD.");
            }
        } else {
            User u = users.save(new User(ADMIN, "Admin", encoder.encode(props.appPassword())));
            hasUsers = true;
            members.save(new WorkspaceMember(WorkspaceContext.DEFAULT, u.getId(), Role.OWNER));
            log.info("Đã tạo tài khoản admin (mật khẩu = APP_PASSWORD), chủ workspace 1.");
        }
    }

    /** Nhập dữ liệu cũ của bản Node có mật khẩu (scrypt): thành tài khoản "admin" chủ workspace 1, nếu chưa có tài khoản nào */
    @Transactional
    public void importLegacyPassword(String nodeScryptHash) {
        if (nodeScryptHash == null || nodeScryptHash.isEmpty() || users.count() > 0) return;
        User u = users.save(new User(ADMIN, "Admin", "{" + Passwords.LEGACY + "}" + nodeScryptHash));
        hasUsers = true;
        members.save(new WorkspaceMember(WorkspaceContext.DEFAULT, u.getId(), Role.OWNER));
    }

    /** Dữ liệu cho giao diện về người đang đăng nhập */
}
