package com.louiekury.media.upload;

import com.louiekury.media.MediaApplication;
import com.louiekury.media.upload.application.port.out.UploadRecordStore;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class UploadRecordStoreSpringContextTest {
    @Test void repositoryBeanStartsWithDefaultSpringBootProxySettings() {
        try (var context = new SpringApplicationBuilder(MediaApplication.class, TestDataSource.class)
                .web(WebApplicationType.NONE)
                .properties("spring.flyway.enabled=false", "spring.sql.init.mode=never")
                .run()) {
            assertNotNull(context.getBean(UploadRecordStore.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDataSource {
        @Bean DataSource dataSource() { return mock(DataSource.class); }
    }
}
