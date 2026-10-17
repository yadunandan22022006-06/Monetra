package com.monetra;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the same-account check in front of every private API request. Signing in and out are left alone. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SameAccountGuard guard;

    public WebConfig(SameAccountGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/paypal/webhook");
    }
}
