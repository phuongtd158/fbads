package com.fbads.company;

import com.fbads.web.Ok;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Báo cáo lên hệ thống công ty: cài đặt, kiểm tra kết nối, danh sách Team của công ty, các bản báo cáo theo mốc. */
@RestController
@RequestMapping("/api/company")
public class CompanyController {
    private final CompanyReportService company;

    public CompanyController(CompanyReportService company) {
        this.company = company;
    }

    @GetMapping
    CompanyOverview overview() {
        return company.overview();
    }

    @PostMapping("/config")
    CompanyConfigSaved config(@RequestBody(required = false) CompanyConfigPatch body) {
        return company.saveConfig(body);
    }

    /** Đăng nhập thử bằng tài khoản đã lưu → tên người dùng + danh sách Team để chọn */
    @PostMapping("/test")
    CompanyLogin test() {
        return company.test();
    }

    @GetMapping("/teams")
    CompanyTeams teams() {
        return company.teams();
    }

    /** Tạo bản báo cáo của một mốc ngay (không chờ đến giờ), vd để thử hoặc làm lại sau khi đổi cấu hình */
    @PostMapping("/reports/build")
    CompanyBuilt build(@RequestBody(required = false) CompanyBuild body) {
        CompanyBuild b = body == null ? CompanyBuild.EMPTY : body;
        return company.buildNow(b.slot(), Boolean.TRUE.equals(b.telegram()));
    }

    @PostMapping("/reports/{id}")
    CompanyReport update(@PathVariable String id, @RequestBody(required = false) CompanyReportPatch body) {
        return company.update(id, body);
    }

    @PostMapping("/reports/{id}/send")
    CompanyReport send(@PathVariable String id) {
        return company.send(id, CompanyReportService.SOURCE);
    }

    @PostMapping("/reports/{id}/update")
    CompanyReport updateRemote(@PathVariable String id, @RequestBody(required = false) CompanyUpdate body) {
        String reason = (body == null ? CompanyUpdate.EMPTY : body).reason();
        return company.updateRemote(id, reason, CompanyReportService.SOURCE);
    }

    /** Lấy trạng thái từ công ty (đã nộp / nộp muộn, lần sửa, đã khoá) cho các bản báo cáo gần đây */
    @PostMapping("/sync")
    CompanySynced sync() {
        int n = company.sync();
        return new CompanySynced(n, company.list());
    }

    @DeleteMapping("/reports/{id}")
    Ok remove(@PathVariable String id) {
        company.remove(id);
        return Ok.OK;
    }
}
