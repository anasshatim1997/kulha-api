package ma.kulha.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            Converter<Jwt, AbstractAuthenticationToken> jwtConverter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/auth/register", "/api/v1/auth/verify-email", "/api/v1/auth/resend-verification",
                        "/api/v1/auth/login", "/api/v1/auth/google", "/api/v1/auth/refresh", "/api/v1/auth/logout",
                        "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/offers", "/api/v1/offers/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/offers/nearby").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/me").authenticated()
                .requestMatchers("/api/v1/me/notification-preferences", "/api/v1/me/preferences").authenticated()
                .requestMatchers("/api/v1/notifications/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/merchants/apply").authenticated()
                 .requestMatchers(HttpMethod.GET, "/api/v1/merchants/me").hasAnyRole("CUSTOMER", "MERCHANT", "MERCHANT_OWNER")
                .requestMatchers(HttpMethod.PUT, "/api/v1/merchants/me").hasAnyRole("CUSTOMER", "MERCHANT", "MERCHANT_OWNER")
                .requestMatchers("/api/v1/merchants/me/staff", "/api/v1/merchants/me/staff/**").hasAnyRole("MERCHANT", "MERCHANT_OWNER")
                .requestMatchers("/api/v1/merchants/me/offers", "/api/v1/merchants/me/offers/**").hasAnyRole("MERCHANT", "MERCHANT_OWNER")
                .requestMatchers(HttpMethod.GET, "/api/v1/reservations/availability").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.GET, "/api/v1/reservations").hasRole("CUSTOMER")
                 .requestMatchers(HttpMethod.POST, "/api/v1/reservations/collect").hasAnyRole("MERCHANT", "MERCHANT_OWNER", "MERCHANT_STAFF")
                .requestMatchers(HttpMethod.POST, "/api/v1/reservations").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/reservations/*/cancel").hasRole("CUSTOMER")
                 .requestMatchers(HttpMethod.GET, "/api/v1/reservations/*").hasAnyRole("CUSTOMER", "MERCHANT", "MERCHANT_OWNER", "MERCHANT_STAFF", "ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }
}
