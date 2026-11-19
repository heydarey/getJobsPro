package com.getjobs.application.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/** 在 Java 中设置上传限制，避免要求用户修改 application.yaml。 */
@Configuration
public class BossResumeImageUploadConfig {

    /**
     * 配置 multipart 上传限制：单文件 10MB，单次请求最多 5 张图片。
     *
     * @return MultipartConfigElement，Spring 使用的上传限制配置
     */
    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(10));
        // 多图上传最多 5 张，每张 10MB；multipart 有少量边界和请求头开销，请求上限需要略大于文件总上限。
        factory.setMaxRequestSize(DataSize.ofMegabytes(51));
        return factory.createMultipartConfig();
    }
}
