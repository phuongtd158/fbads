package com.fbads.entity;

import com.fbads.common.JsonConverters;
import com.fbads.dto.AdLevel;
import com.fbads.dto.AdObject;
import com.fbads.dto.Metrics;
import com.fbads.engine.Action;
import com.fbads.engine.ActionType;
import com.fbads.engine.BudgetMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Các phần lồng nhau của nhật ký: ghi ra JSON đúng khoá như bản Node, và đọc lại được nhật ký cũ. */
class LogPartsTest {
    static <T> String write(JsonConverters.Of<T> c, T v) { return c.convertToDatabaseColumn(v); }

    static AdObject adset() {
        AdObject o = new AdObject();
        o.id = "a1"; o.name = "Nhóm 1"; o.level = AdLevel.ADSET; o.status = "ACTIVE"; o.effective = "ACTIVE";
        o.accountId = "act_1"; o.accountName = "TK 1";
        o.metrics = new Metrics(1000, 0, 0, 0, 2, 500.0, 0, null, 0, 0, 0, 0, 0);
        return o;
    }

    @Test
    void writesSameKeysAsBefore() {
        AdObject o = adset();
        assertThat(write(new LogTarget.Converter(), LogTarget.of(o)))
                .isEqualTo("{\"id\":\"a1\",\"name\":\"Nhóm 1\",\"level\":\"adset\",\"accountId\":\"act_1\",\"accountName\":\"TK 1\"}");
        assertThat(write(new LogTarget.Converter(), LogTarget.idOnly("x"))).isEqualTo("{\"id\":\"x\"}");
        // Không có ngân sách riêng: vẫn ghi dailyBudget: null (giao diện và hoàn tác cần biết)
        assertThat(write(new LogSnapshot.Converter(), LogSnapshot.of(o))).isEqualTo("{\"level\":\"adset\",\"status\":\"ACTIVE\","
                + "\"effective\":\"ACTIVE\",\"dailyBudget\":null,\"metrics\":{\"spend\":1000.0,\"results\":2.0,\"cpa\":500.0,\"roas\":null}}");
        assertThat(write(new LogAction.Converter(), LogAction.of(Action.off()))).isEqualTo("{\"type\":\"off\"}");
        assertThat(write(new LogAction.Converter(), LogAction.of(Action.budget(BudgetMode.PERCENT, 20, 500000, 0)).reverted()))
                .isEqualTo("{\"type\":\"budget\",\"mode\":\"percent\",\"value\":20.0,\"max\":500000.0,\"revert\":true}");
        assertThat(write(new LogChange.Converter(), LogChange.status(false))).isEqualTo("{\"status\":\"PAUSED\"}");
        assertThat(write(new LogError.Converter(), LogError.of("Lỗi"))).isEqualTo("{\"message\":\"Lỗi\"}");
        assertThat(write(new LogCondition.Converter(), LogCondition.spendOver(100, 120))).isEqualTo("{\"metric\":\"spend\","
                + "\"op\":\">\",\"range\":\"today\",\"threshold\":100.0,\"actual\":120.0,\"minSpend\":0.0,\"spend\":120.0}");
    }

    @Test
    void readsOldLogs() {
        // Nhật ký cũ (bản Node): số nguyên, khoá lạ, thiếu khoá
        LogAction a = new LogAction.Converter().convertToEntityAttribute("{\"type\":\"budget\",\"mode\":\"set\",\"value\":515000,\"x\":1}");
        assertThat(a.type()).isEqualTo(ActionType.BUDGET);
        assertThat(a.value()).isEqualTo(515000.0);
        assertThat(a.changesObject()).isTrue();
        assertThat(new LogAction.Converter().convertToEntityAttribute("{\"type\":\"notify\"}").changesObject()).isFalse();

        LogChange after = new LogChange.Converter().convertToEntityAttribute("{\"dailyBudget\":515000}");
        assertThat(after.changesBudget()).isTrue();
        assertThat(after.changesStatus()).isFalse();

        LogSnapshot before = new LogSnapshot.Converter().convertToEntityAttribute("{\"status\":\"PAUSED\",\"dailyBudget\":null}");
        assertThat(before.wasActive()).isFalse();
        assertThat(before.dailyBudget()).isNull();
        assertThat(before.metrics()).isNull();

        LogCondition c = new LogCondition.Converter().convertToEntityAttribute(
                "{\"metric\":\"cpa\",\"actual\":null,\"inf\":true,\"conditions\":[{\"metric\":\"cpa\",\"hit\":true}],\"ladder\":{\"step\":2}}");
        assertThat(c.conditions().getFirst().hit()).isTrue();
        assertThat(c.ladder().step()).isEqualTo(2);

        assertThat(new LogError.Converter().convertToEntityAttribute("{\"message\":\"m\",\"code\":190,\"request\":{\"method\":\"GET\"}}").code())
                .isEqualTo(190);
    }
}
