package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {


    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(//不用拦截的路径
                  "/user/code",
                  "/user/login",
                  "/blog/hot",
                  "/shop/**",
                  "/shop-type/**",
                  "/voucher/**",
                  "/upload/**"
                );
    }
}
