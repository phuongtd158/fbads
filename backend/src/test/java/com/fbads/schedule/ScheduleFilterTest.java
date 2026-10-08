package com.fbads.schedule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Điều kiện của lịch "Theo điều kiện": lưu JSON đúng khoá như bản Node, đọc được dữ liệu cũ, câu mô tả. */
class ScheduleFilterTest {
    final ScheduleFilter.Converter conv = new ScheduleFilter.Converter();

    @Test
    void writesOnlyUsedKeys() {
        ScheduleFilter f = new ScheduleFilter("adset", "lt", 100000.0, null, "Sale", "running", true, null);
        assertThat(conv.convertToDatabaseColumn(f))
                .isEqualTo("{\"level\":\"adset\",\"op\":\"lt\",\"x\":100000.0,\"name\":\"Sale\",\"status\":\"running\",\"onlyRunning\":true}");
        assertThat(f.describe()).isEqualTo("Nhóm QC ngân sách dưới 100.000 · tên chứa “Sale” · đang chạy");
    }

    @Test
    void readsOldData() {
        // dữ liệu cũ: chỉ có onlyRunning, không có status
        ScheduleFilter f = conv.convertToEntityAttribute("{\"level\":\"campaign\",\"op\":\"any\",\"name\":\"\",\"onlyRunning\":true}");
        assertThat(f.statusMode()).isEqualTo("running");
        assertThat(f.budgetOp()).isEqualTo("any");
        assertThat(f.describe()).isEqualTo("Chiến dịch · đang chạy");
        assertThat(conv.convertToEntityAttribute("{\"op\":\"any\"}").describe()).isEqualTo("Mọi chiến dịch");
    }
}
