package ma.kulha.notification.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SmtpMailConfigurationTest {

    @Autowired
    JavaMailSenderImpl mailSender;

    @Test
    void smtpTimeoutsAreConfiguredAndNonZero() {
        var properties = mailSender.getJavaMailProperties();

        assertThat(Integer.parseInt(properties.getProperty("mail.smtp.connectiontimeout")))
                .isPositive();
        assertThat(Integer.parseInt(properties.getProperty("mail.smtp.timeout")))
                .isPositive();
        assertThat(Integer.parseInt(properties.getProperty("mail.smtp.writetimeout")))
                .isPositive();
    }

    @Test
    void localProfileDoesNotRequireStartTlsByDefault() {
        assertThat(mailSender.getJavaMailProperties().getProperty("mail.smtp.starttls.required"))
                .isEqualTo("false");
    }
}
