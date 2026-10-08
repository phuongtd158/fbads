package com.fbads.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Log chi tiết để học/gỡ lỗi: method nào được gọi, tham số, kết quả, mất bao lâu; và câu SQL Hibernate chạy.
 * Mỗi tầng một cờ (application.yml, fbads.logging.*), profile dev bật hết:
 *   LOG_CONTROLLER, LOG_SERVICE, LOG_ENGINE, LOG_REPOSITORY, LOG_SQL.
 * Code chia package theo tính năng (schedule/, rule/…), nên tầng nhận ra theo chú thích, không theo package:
 * controller = @RestController, service = @Service, engine = package engine + các lớp chạy theo vòng (ENGINE),
 * repository = interface Spring Data của tool.
 * Tầng nào tắt thì aspect của tầng đó không được tạo, nên bean không bị bọc proxy và không tốn gì.
 * Ghi mọi method không private (nhiều controller để method ở mức package), trừ lời gọi nội bộ trong cùng một class
 * (this.x() không đi qua proxy của Spring AOP).
 */
@Configuration
public class CallLogging {
    /** Các lớp thuộc vòng tự động: package engine, các *Runner (lịch, rule), RuleEvaluator, AlertWatch */
    static final String ENGINE = "(within(com.fbads.engine..*) || within(com.fbads..*Runner) "
            + "|| within(com.fbads.rule.RuleEvaluator) || within(com.fbads..AlertWatch))";

    @Bean
    CallLogger callLogger(ObjectProvider<JsonMapper> json) { return new CallLogger(json); }

    @Bean
    @ConditionalOnBooleanProperty("fbads.logging.controller")
    ControllerCalls controllerCalls(CallLogger calls) { return new ControllerCalls(calls); }

    @Bean
    @ConditionalOnBooleanProperty("fbads.logging.service")
    ServiceCalls serviceCalls(CallLogger calls) { return new ServiceCalls(calls); }

    @Bean
    @ConditionalOnBooleanProperty("fbads.logging.engine")
    EngineCalls engineCalls(CallLogger calls) { return new EngineCalls(calls); }

    @Bean
    @ConditionalOnBooleanProperty("fbads.logging.repository")
    RepositoryCalls repositoryCalls(CallLogger calls) { return new RepositoryCalls(calls); }

    /**
     * LOG_SQL: bật logger SQL của Hibernate (câu SQL + giá trị từng tham số "?").
     * Chạy trước khi tạo bean nào (BeanFactoryPostProcessor), nên thấy cả SQL lúc khởi động (Flyway, nhập dữ liệu).
     */
    @Bean
    static BeanFactoryPostProcessor sqlLogging(Environment env) {
        return beanFactory -> {
            if (!Binder.get(env).bind("fbads.logging.sql", Boolean.class).orElse(false)) return;
            LoggingSystem logging = LoggingSystem.get(CallLogging.class.getClassLoader());
            logging.setLogLevel("org.hibernate.SQL", LogLevel.DEBUG);
            logging.setLogLevel("org.hibernate.orm.jdbc.bind", LogLevel.TRACE);
        };
    }

    private static String typeOf(ProceedingJoinPoint jp) { return ClassUtils.getUserClass(jp.getTarget()).getSimpleName(); }

    @Aspect
    static class ControllerCalls {
        private final CallLogger calls;
        ControllerCalls(CallLogger calls) { this.calls = calls; }

        @Around("execution(!private * *(..)) && @within(org.springframework.web.bind.annotation.RestController)")
        Object around(ProceedingJoinPoint jp) throws Throwable { return calls.log(jp, "controller", typeOf(jp)); }
    }

    @Aspect
    static class ServiceCalls {
        private final CallLogger calls;
        ServiceCalls(CallLogger calls) { this.calls = calls; }

        @Around("execution(!private * *(..)) && @within(org.springframework.stereotype.Service) && !" + ENGINE)
        Object around(ProceedingJoinPoint jp) throws Throwable { return calls.log(jp, "service", typeOf(jp)); }
    }

    /** EngineClock bị bỏ qua: chỉ đọc giờ, được gọi ở mọi nơi (mỗi sự kiện, mỗi dòng nhật ký) nên sẽ lấp hết log */
    @Aspect
    static class EngineCalls {
        private final CallLogger calls;
        EngineCalls(CallLogger calls) { this.calls = calls; }

        @Around("execution(!private * *(..)) && " + ENGINE + " && !within(com.fbads.engine.EngineClock)")
        Object around(ProceedingJoinPoint jp) throws Throwable { return calls.log(jp, "engine", typeOf(jp)); }
    }

    /**
     * Repository là interface, Spring Data tạo bản cài đặt lúc chạy. Bắt mọi method của Repository (kể cả save, findById
     * kế thừa từ JpaRepository), rồi chỉ ghi repository của tool (com.fbads.*), tên in ra là tên interface.
     */
    @Aspect
    static class RepositoryCalls {
        private final CallLogger calls;
        RepositoryCalls(CallLogger calls) { this.calls = calls; }

        @Around("execution(public * org.springframework.data.repository.Repository+.*(..))")
        Object around(ProceedingJoinPoint jp) throws Throwable {
            for (Class<?> i : jp.getThis().getClass().getInterfaces()) {
                if (i.getName().startsWith("com.fbads.")) return calls.log(jp, "repository", i.getSimpleName());
            }
            return jp.proceed();
        }
    }
}
