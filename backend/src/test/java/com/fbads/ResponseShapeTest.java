package com.fbads;

import com.fbads.company.CompanyConfig;
import com.fbads.company.CompanyConfigRepository;
import com.fbads.company.CompanyReportRepository;
import com.fbads.company.CompanyRules;
import com.fbads.facebook.FacebookState;
import com.fbads.security.WorkspaceContext;
import com.fbads.settings.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hình dạng JSON (tên trường + kiểu giá trị) của các API hay dùng phải giữ nguyên khi đổi code bên trong, ví dụ đổi
 * Map sang record. Mẫu chuẩn sinh từ code cũ: chạy với -Dfbads.shapes.write=true để ghi lại
 * src/test/resources/fixtures/response-shapes.json (chỉ làm khi CỐ Ý đổi API).
 */
class ResponseShapeTest extends IntegrationBase {
    static final Set<String> NULLABLE_NUMBERS = Set.of("cpa", "roas");
    static final Path FIXTURE = Path.of("src/test/resources/fixtures/response-shapes.json");

    @LocalServerPort int port;
    @Autowired SettingsService settings;
    @Autowired FacebookState fbState;
    @Autowired CompanyConfigRepository configs;
    @Autowired CompanyReportRepository reports;

    CompanyStub co;
    WorkspaceContext.Scope ws;

    @BeforeEach
    void setUp() throws Exception {
        ws = WorkspaceContext.enter(WorkspaceContext.DEFAULT);
        co = new CompanyStub();
        settings.update(s -> s.setMock(true));
        reports.deleteAll();
        fbState.resetMock();
        fbState.resetCache();
        CompanyConfig c = configs.findById(WorkspaceContext.DEFAULT).orElseGet(() -> new CompanyConfig(WorkspaceContext.DEFAULT));
        c.setEnabled(true);
        c.setMode("approve");
        c.setSlots(new ArrayList<>(CompanyRules.SLOTS));
        c.setBaseUrl(co.base());
        c.setEmail("mkt@congty.vn");
        c.setPassword(CompanyStub.GOOD_LOGIN);
        c.setTeams(List.of(CompanyReportTest.TEAM));
        configs.save(c);
    }

    @AfterEach
    void tearDown() {
        reports.deleteAll();
        configs.deleteAll();
        fbState.resetMock();
        fbState.resetCache();
        co.close();
        ws.close();
    }

    /** Kiểu của từng giá trị thay cho giá trị: { "at": "number", "items": [ {…} ] }. Mảng: lấy phần tử đầu. */
    static JsonNode shape(JsonNode n) {
        if (n == null || n.isNull()) return Api.JSON.getNodeFactory().textNode("null");
        if (n.isObject()) {
            ObjectNode o = Api.JSON.createObjectNode();
            for (String k : new TreeSet<>(n.propertyNames())) {
                // cpa/roas: số hoặc null tuỳ dữ liệu giả lúc chạy (sáng sớm chưa có kết quả) nên không so kiểu cụ thể
                o.set(k, NULLABLE_NUMBERS.contains(k) && !n.get(k).isContainer() ? Api.JSON.getNodeFactory().textNode("number|null")
                        : shape(n.get(k)));
            }
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = Api.JSON.createArrayNode();
            if (!n.isEmpty()) a.add(shape(n.get(0)));
            return a;
        }
        return Api.JSON.getNodeFactory().textNode(n.isNumber() ? "number" : n.isBoolean() ? "boolean" : "string");
    }

    @Test
    void responsesKeepTheirShape() throws Exception {
        Api api = new Api(port);
        ObjectNode got = Api.JSON.createObjectNode();
        java.util.function.BiConsumer<String, Api.Res> keep = (name, r) -> {
            assertThat(r.status()).as(name + " " + r.body()).isEqualTo(200);
            got.set(name, shape(r.body()));
        };

        Api.Res objects = api.get("/api/objects");
        keep.accept("GET /api/objects", objects);
        JsonNode budgeted = null;
        for (JsonNode o : objects.body().get("items")) {
            if (budgeted == null && o.hasNonNull("dailyBudget")) budgeted = o;
        }
        String id = budgeted.get("id").asString();
        keep.accept("GET /api/insights", api.get("/api/insights?range=last_7d"));
        keep.accept("POST /api/objects/{id}/status", api.post("/api/objects/" + id + "/status", Map.of("on", false, "name", "x")));
        keep.accept("POST /api/objects/{id}/budget", api.post("/api/objects/" + id + "/budget", Map.of("amount", 300000, "name", "x")));
        keep.accept("GET /api/objects/{id}/trend", api.get("/api/objects/" + id + "/trend?days=7"));
        Api.Res logs = api.get("/api/logs");
        keep.accept("GET /api/logs", logs);
        keep.accept("POST /api/logs/{id}/undo", api.post("/api/logs/" + logs.body().get(0).get("id").asString() + "/undo",
                Map.of()));
        keep.accept("GET /api/state", api.get("/api/state"));
        // lịch, rule, cài đặt do test khác để lại có thể khác nhau: chỉ so tầng ngoài cùng
        ObjectNode state = (ObjectNode) got.get("GET /api/state");
        state.put("schedules", "array");
        state.put("rules", "array");
        ObjectNode st = (ObjectNode) state.get("settings");
        for (String k : new TreeSet<>(st.propertyNames())) {
            if (st.get(k).isContainer()) st.put(k, st.get(k).isArray() ? "array" : "object");
        }
        keep.accept("GET /api/storage", api.get("/api/storage"));

        keep.accept("GET /api/company", api.get("/api/company"));
        keep.accept("POST /api/company/config", api.post("/api/company/config", Map.of("leadMin", "5")));
        keep.accept("POST /api/company/test", api.post("/api/company/test", Map.of()));
        keep.accept("GET /api/company/teams", api.get("/api/company/teams"));
        Api.Res built = api.post("/api/company/reports/build", Map.of("slot", 12));
        keep.accept("POST /api/company/reports/build", built);
        String rid = built.body().get("built").get(0).asString();
        keep.accept("POST /api/company/reports/{id}", api.post("/api/company/reports/" + rid, Map.of("notes", "ghi chú")));
        keep.accept("POST /api/company/sync", api.post("/api/company/sync", Map.of()));
        keep.accept("DELETE /api/company/reports/{id}", api.delete("/api/company/reports/" + rid));

        String pretty = Api.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(got);
        if (Boolean.getBoolean("fbads.shapes.write")) Files.writeString(FIXTURE, pretty + "\n");
        JsonNode want = Api.JSON.readTree(Files.readString(FIXTURE));
        assertThat(pretty).isEqualTo(Api.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(want));
    }
}
