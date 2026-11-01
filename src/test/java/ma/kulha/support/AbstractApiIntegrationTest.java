package ma.kulha.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Base class for API tests: a clean database, MockMvc with Spring Security, a recording notification
 * double, and small helpers. Helper names avoid "get", "post" and "put" so they never hide
 * the static imports of the same names.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestNotificationConfig.class)
public abstract class AbstractApiIntegrationTest {

    protected static final String PASSWORD = "a-long-enough-password";

    @Autowired
    protected WebApplicationContext context;

    @Autowired
    protected UserRepository users;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected RecordingNotificationApi emails;

    protected MockMvc mvc;

    @BeforeEach
    protected void resetState() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("DELETE FROM merchant.merchants").update();
        jdbc.sql("DELETE FROM identity.users").update(); // tokens and roles go with it (ON DELETE CASCADE)
        jdbc.sql("TRUNCATE admin.audit_log").update();
        emails.clear();
    }

    // ---------- users and tokens ----------

    protected User createUser(String email, boolean emailVerified, Role... extraRoles) {
        User user = User.register(email, passwordEncoder.encode(PASSWORD), "Test User", null, Instant.now());
        users.insert(user);
        if (emailVerified) {
            users.markEmailVerified(user.id(), Instant.now());
        }
        for (Role role : extraRoles) {
            users.addRole(user.id(), role);
        }
        return users.findById(user.id()).orElseThrow();
    }

    protected String tokenFor(User user) throws Exception {
        MockHttpServletResponse response = doPost("/api/v1/auth/login",
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(user.email(), PASSWORD), null);
        return field(response, "accessToken");
    }

    protected String tokenForEmail(String email) throws Exception {
        return tokenFor(users.findByEmail(email).orElseThrow());
    }

    // ---------- requests ----------

    protected MockHttpServletResponse doPost(String path, String json, String token) throws Exception {
        return send(post(path).contentType(MediaType.APPLICATION_JSON).content(json), token);
    }

    protected MockHttpServletResponse doPostEmpty(String path, String token) throws Exception {
        return send(post(path), token);
    }

    protected MockHttpServletResponse doPut(String json, String token) throws Exception {
        return send(put("/api/v1/merchants/me").contentType(MediaType.APPLICATION_JSON).content(json), token);
    }

    protected MockHttpServletResponse doGet(String path, String token) throws Exception {
        return send(get(path), token);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    // ---------- data ----------

    protected static String profileJson(String name) {
        return """
                {"name":"%s","businessType":"BAKERY","address":"12 rue des Fleurs","city":"Casablanca",\
                "phone":"+212600000000","openingHours":"Lun-Sam 7h-20h"}
                """.formatted(name);
    }

    /** Creates a verified customer, applies as a merchant and returns the merchant id. */
    protected String applyAs(String email, String merchantName) throws Exception {
        User owner = createUser(email, true);
        MockHttpServletResponse response = doPost("/api/v1/merchants/apply", profileJson(merchantName), tokenFor(owner));
        assertThat(response.getStatus()).isEqualTo(201);
        return field(response, "id");
    }

    protected long auditCount(String targetId) {
        return jdbc.sql("SELECT count(*) FROM admin.audit_log WHERE target_type = :t AND target_id = :id")
                .param("t", "MERCHANT").param("id", targetId).query(Long.class).single();
    }

    protected static String field(MockHttpServletResponse response, String name) throws Exception {
        String body = response.getContentAsString();
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("field '%s' in response: %s", name, body).isTrue();
        return matcher.group(1);
    }
}
