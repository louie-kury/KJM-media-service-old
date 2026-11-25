package com.louiekury.media.upload;

import com.louiekury.media.MediaApplication;
import com.louiekury.media.upload.application.port.out.UploadRecordStore;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

class UploadRecordStoreSpringContextTest {
    @Test void applicationScanDoesNotRegisterTestDataSource() {
        try (var context = new SpringApplicationBuilder(MediaApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.flyway.enabled=false", "spring.sql.init.mode=never",
                        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/media_test_unused",
                        "spring.datasource.username=test", "spring.datasource.password=test")
                .run()) {
            assertFalse(mockingDetails(context.getBean(DataSource.class)).isMock());
        }
    }
    @Test void repositoryBeanStartsWithDefaultSpringBootProxySettings() {
        try (var context = new SpringApplicationBuilder(MediaApplication.class, TestDataSource.class)
                .web(WebApplicationType.NONE)
                .properties("spring.flyway.enabled=false", "spring.sql.init.mode=never")
                .run()) {
            assertNotNull(context.getBean(UploadRecordStore.class));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDataSource {
        @Bean DataSource dataSource() { return mock(DataSource.class); }
    }
}
