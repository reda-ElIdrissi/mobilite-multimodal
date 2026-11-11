package com.transport.autopartage.vehiculeRoute;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Profile("demo & demo-expiry")
class DemoConfirmDelay implements WebMvcConfigurer {
    private final Duration delay;

    DemoConfirmDelay(@Value("${mobility.demo.confirm-delay:PT35S}") Duration delay) {
        if (delay.isNegative() || delay.isZero() || delay.compareTo(Duration.ofSeconds(40)) > 0) {
            throw new IllegalArgumentException("Invalid demonstration delay");
        }
        this.delay = delay;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws InterruptedException {
                if ("POST".equals(request.getMethod())) {
                    Thread.sleep(delay.toMillis());
                }
                return true;
            }
        }).addPathPatterns("/internal/v1/holds/*/confirm");
    }
}
