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

    /**
     * @param jwtConverter turns a validated JWT into an authenticated user. It is provided by the
     *                     identity module and reads the user and roles from the database.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            Converter<Jwt, AbstractAuthenticationToken> jwtConverter) throws Exception {
        http
            // Stateless API: credentials travel in the Authorization header, never in cookies,
            // so there is no ambient credential for a CSRF attack to abuse. The web app (Next.js)
            // keeps its own cookies and protects them itself.
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/auth/register",
                        "/api/v1/auth/verify-email",
                        "/api/v1/auth/resend-verification",
                        "/api/v1/auth/login",
                        "/api/v1/auth/google",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/forgot-password",
                        "/api/v1/auth/reset-password").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/merchants/apply").authenticated()
                .requestMatchers("/api/v1/merchants/me").authenticated()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().denyAll()   // deny by default; open routes explicitly, one at a time
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }
}
