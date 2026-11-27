package com.joylin.waterkb.config;

import com.joylin.waterkb.security.PermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册权限拦截器与静态资源缓存策略。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;

    /**
     * 构造Web配置。
     */
    public WebConfig(PermissionInterceptor permissionInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
    }

    /**
     * 注册权限拦截器。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor).addPathPatterns("/api/**");
    }

    /**
     * 配置静态资源不缓存。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore());
    }
}
