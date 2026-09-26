package com.fbads.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/** Cấu hình riêng của tool (khoá fbads.* trong application.yml). */
@ConfigurationProperties("fbads")
public record AppProperties(
        String publicDir,
        String appPassword,
        String publicUrl,
        boolean trustProxy,
        boolean render,
        String clientIpHeader,
        Engine engine,
        @Name("import") Import importer) {

    public record Engine(boolean enabled, long tickMs) {}

    /** Nhập dữ liệu cũ từ bản Node (data.json hoặc Upstash), chỉ chạy khi DB còn trống. */
    public record Import(String file, boolean upstash, String upstashUrl, String upstashToken, String dataKey) {}

    public AppProperties {
        if (appPassword == null) appPassword = "";
        if (publicUrl == null) publicUrl = "";
        if (clientIpHeader == null) clientIpHeader = "";
        if (engine == null) engine = new Engine(true, 30000);
        if (importer == null) importer = new Import("", false, "", "", "");
    }
}
