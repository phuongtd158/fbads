package com.fbads;

import com.fbads.auth.NodeScryptPasswordEncoder;
import com.fbads.engine.EngineClock;
import com.fbads.engine.ScheduleRunner;
import com.fbads.automation.Schedule;
import com.fbads.importer.UpstashCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dữ liệu do bản Node tạo ra (fixtures/node-compat.json) phải đọc được ở bản Java — và ngược lại. */
class NodeCompatTest {
    static final JsonNode FIX = read("/fixtures/node-compat.json");

    static JsonNode read(String path) {
        try (InputStream in = NodeCompatTest.class.getResourceAsStream(path)) {
            return JsonMapper.builder().build().readTree(in);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void passwordHashFromNodeStillWorks() {
        NodeScryptPasswordEncoder enc = new NodeScryptPasswordEncoder();
        assertThat(enc.matches(FIX.get("password").asString(), FIX.get("passwordHash").asString())).isTrue();
        assertThat(enc.matches("sai-mat-khau", FIX.get("passwordHash").asString())).isFalse();
        String mine = enc.encode("MatKhau@2026");
        assertThat(mine).matches("[0-9a-f]{32}:[0-9a-f]{128}");
        assertThat(enc.matches("MatKhau@2026", mine)).isTrue();
    }

    @Test
    void upstashDataFromNodeDecodes() {
        String key = FIX.get("dataKey").asString();
        assertThat(UpstashCodec.decode(FIX.get("encoded").asString(), key)).isEqualTo(FIX.get("plain").asString());
        assertThat(UpstashCodec.decode(UpstashCodec.encode("{\"a\":1}", key), key)).isEqualTo("{\"a\":1}");
        assertThatThrownBy(() -> UpstashCodec.decode(FIX.get("encoded").asString(), "khoa-sai-khoa-sai-123")).hasMessageContaining("DATA_KEY sai");
        assertThatThrownBy(() -> UpstashCodec.decode("xyz", key)).hasMessageContaining("không đúng định dạng");
    }

    @Test
    void windowSchedulesCrossMidnight() {
        Schedule s = new Schedule();
        s.setAction("window");
        s.setWindow(Map.of("on", "22:00", "off", "06:00"));
        s.setDays(List.of(1));
        assertThat(ScheduleRunner.windowIsOn(s, new EngineClock.Now("2026-09-28", EngineClock.toMin("23:30"), 1))).isTrue();
        assertThat(ScheduleRunner.windowIsOn(s, new EngineClock.Now("2026-09-29", EngineClock.toMin("05:00"), 2))).isTrue();
        assertThat(ScheduleRunner.windowIsOn(s, new EngineClock.Now("2026-09-29", EngineClock.toMin("07:00"), 2))).isFalse();
        assertThat(ScheduleRunner.events(s)).extracting(ScheduleRunner.Event::action).containsExactlyInAnyOrder("on", "off");
    }
}
