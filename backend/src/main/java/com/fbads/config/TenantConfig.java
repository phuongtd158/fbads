package com.fbads.config;

import com.fbads.security.WorkspaceContext;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Chia dữ liệu theo workspace bằng @TenantId của Hibernate: entity có trường @TenantId (Schedule, Rule, LogEntry…)
 * thì mọi câu truy vấn tự thêm "where workspace_id = ?" và mọi dòng mới tự ghi workspace_id, lấy từ WorkspaceContext.
 * Luồng chưa gắn workspace → tenant 0 (không có workspace 0): đọc ra rỗng, ghi thì bị khoá ngoại từ chối.
 */
@Configuration
public class TenantConfig implements CurrentTenantIdentifierResolver<Long>, HibernatePropertiesCustomizer {
    static final long NONE = 0L;

    @Override
    public Long resolveCurrentTenantIdentifier() {
        Long id = WorkspaceContext.current();
        return id == null ? NONE : id;
    }

    @Override
    public boolean validateExistingCurrentSessions() { return false; }

    @Override
    public void customize(Map<String, Object> props) { props.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this); }
}
