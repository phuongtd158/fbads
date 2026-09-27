package com.fbads.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Phục vụ bản build giao diện Vue (thư mục public/, đặt bằng PUBLIC_DIR).
 * File trong assets/ có mã băm trong tên → cache 1 năm; còn lại no-cache. Đường dẫn lạ → index.html (ứng dụng 1 trang).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final Path root;

    public WebConfig(AppProperties props) { this.root = Path.of(props.publicDir()).toAbsolutePath().normalize(); }

    /** "/" không đi qua bộ tìm file ở dưới (đường dẫn rỗng) → chuyển thẳng tới index.html */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = root.toUri().toString();
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(location + "assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        if (path.startsWith("api/") || path.startsWith("actuator/")) return null;
                        Resource r = location.createRelative(path);
                        if (r.exists() && r.isReadable() && !path.isEmpty()) return r;
                        FileSystemResource index = new FileSystemResource(root.resolve("index.html"));
                        return index.exists() ? index : null;
                    }
                });
    }
}
