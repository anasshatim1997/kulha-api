package ma.kulha.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestNotificationConfig {

    @Bean
    @Primary
    RecordingNotificationApi recordingNotificationApi() {
        return new RecordingNotificationApi();
    }
}
