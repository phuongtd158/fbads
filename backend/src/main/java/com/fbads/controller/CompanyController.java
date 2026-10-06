package com.fbads.controller;

import com.fbads.company.CompanyReportService;
import com.fbads.dto.CompanyConfigPatch;
import com.fbads.dto.CompanyReportPatch;
import com.fbads.dto.Requests;
import com.fbads.entity.CompanyReport;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Báo cáo lên hệ thống công ty: cài đặt, kiểm tra kết nối, danh sách Team của công ty, các bản báo cáo theo mốc. */
@RestController
@RequestMapping("/api/company")
public class CompanyController {
    private final CompanyReportService company;

    public CompanyController(CompanyReportService company) { this.company = company; }

    @GetMapping
    Map<String, Object> overview() { return company.overview(); }

    @PostMapping("/config")
    Map<String, Object> config(@RequestBody(required = false) CompanyConfigPatch body) { return company.saveConfig(body); }

    /** Đăng nhập thử bằng tài khoản đã lưu → tên người dùng + danh sách Team để chọn */
    @PostMapping("/test")
    Map<String, Object> test() { return company.test(); }

    @GetMapping("/teams")
    Map<String, Object> teams() { return company.teams(); }

    /** Tạo bản báo cáo của một mốc ngay (không chờ đến giờ), vd để thử hoặc làm lại sau khi đổi cấu hình */
    @PostMapping("/reports/build")
    Map<String, Object> build(@RequestBody(required = false) Requests.CompanyBuild body) {
        Requests.CompanyBuild b = body == null ? Requests.CompanyBuild.EMPTY : body;
        return company.buildNow(b.slot(), Boolean.TRUE.equals(b.telegram()));
    }

    @PostMapping("/reports/{id}")
    CompanyReport update(@PathVariable String id, @RequestBody(required = false) CompanyReportPatch body) {
        return company.update(id, body);
    }

    @PostMapping("/reports/{id}/send")
    CompanyReport send(@PathVariable String id) { return company.send(id, CompanyReportService.SOURCE); }

    @PostMapping("/reports/{id}/update")
    CompanyReport updateRemote(@PathVariable String id, @RequestBody(required = false) Requests.CompanyUpdate body) {
        return company.updateRemote(id, (body == null ? Requests.CompanyUpdate.EMPTY : body).reason(), CompanyReportService.SOURCE);
    }

    /** Lấy trạng thái từ công ty (đã nộp / nộp muộn, lần sửa, đã khoá) cho các bản báo cáo gần đây */
    @PostMapping("/sync")
    Map<String, Object> sync() {
        int n = company.sync();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("synced", n);
        m.put("reports", company.list());
        return m;
    }

    @DeleteMapping("/reports/{id}")
    Map<String, Object> remove(@PathVariable String id) {
        company.remove(id);
        return Map.of("ok", true);
    }
}
