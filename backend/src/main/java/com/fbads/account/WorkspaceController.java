package com.fbads.account;

import com.fbads.common.ApiException;
import com.fbads.dto.Responses.Ok;
import com.fbads.security.WorkspaceContext;
import com.fbads.security.WorkspaceFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Workspace và thành viên. Quyền do WorkspaceFilter kiểm tra trước:
 *  - /api/workspaces (tạo mới), /api/workspaces/switch: người đã đăng nhập nào cũng được;
 *  - xem thành viên: mọi thành viên; đổi tên workspace, thêm/sửa/xoá thành viên: OWNER.
 */
@RestController
@RequestMapping("/api")
public class WorkspaceController {

    private final AuthService auth;

    public WorkspaceController(AuthService auth) { this.auth = auth; }

    private static long signedInUser(HttpServletRequest req) {
        Long uid = WorkspaceFilter.userId(req);
        if (uid == null) throw new ApiException(400, "Chưa có tài khoản nào. Hãy tạo tài khoản trước (Cài đặt → Bảo mật).");
        return uid;
    }

    /** Chọn workspace làm việc (lưu trong phiên đăng nhập) */
    @PostMapping("/workspaces/switch")
    Ok switchTo(@RequestBody(required = false) WorkspaceSwitch b, HttpServletRequest req) {
        long uid = signedInUser(req);
        long id = (b == null ? WorkspaceSwitch.EMPTY : b).id();
        if (auth.role(id, uid) == null) throw new ApiException(403, "Bạn không thuộc workspace này");
        req.getSession().setAttribute(WorkspaceFilter.SESSION_WS, id);
        return Ok.OK;
    }

    /** Tạo workspace mới (vd. cho một khách hàng khác): người tạo là chủ, chuyển sang workspace đó luôn */
    @PostMapping("/workspaces")
    AuthService.Membership create(@RequestBody(required = false) WorkspaceName b, HttpServletRequest req) {
        long uid = signedInUser(req);
        Workspace w = auth.createWorkspace(uid, (b == null ? WorkspaceName.EMPTY : b).name());
        req.getSession().setAttribute(WorkspaceFilter.SESSION_WS, w.getId());
        return new AuthService.Membership(w.getId(), w.getName(), Role.OWNER);
    }

    /** Đổi tên workspace đang chọn */
    @PostMapping("/workspace")
    Ok rename(@RequestBody(required = false) WorkspaceName b) {
        auth.renameWorkspace(WorkspaceContext.require(), (b == null ? WorkspaceName.EMPTY : b).name());
        return Ok.OK;
    }

    @GetMapping("/members")
    List<AuthService.Member> members() { return auth.members(WorkspaceContext.require()); }

    /** { username, name?, password? (khi tạo tài khoản mới), role } */
    @PostMapping("/members")
    AuthService.Member add(@RequestBody(required = false) MemberAdd body, HttpServletRequest req) {
        signedInUser(req);
        MemberAdd b = body == null ? MemberAdd.EMPTY : body;
        return auth.addMember(WorkspaceContext.require(), b.username(), b.name(), b.password(), Role.parse(b.role()));
    }

    @PostMapping("/members/{userId}/role")
    Ok role(@PathVariable long userId, @RequestBody(required = false) RoleChange b) {
        auth.setRole(WorkspaceContext.require(), userId, Role.parse((b == null ? RoleChange.EMPTY : b).role()));
        return Ok.OK;
    }

    @DeleteMapping("/members/{userId}")
    Ok remove(@PathVariable long userId) {
        auth.removeMember(WorkspaceContext.require(), userId);
        return Ok.OK;
    }
}
