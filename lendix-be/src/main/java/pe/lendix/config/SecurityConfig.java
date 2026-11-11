package pe.lendix.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, @Value("${LENDIX_AUTH_REQUIRED:true}") boolean authRequired) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> {
                    if (authRequired) exchanges.pathMatchers("/api/v1/health", "/actuator/health").permitAll().anyExchange().authenticated();
                    else exchanges.anyExchange().permitAll();
                })
                .httpBasic(basic -> {})
                .build();
    }

    @Bean
    ReactiveUserDetailsService users(@Value("${LENDIX_ADMIN_USERNAME:admin}") String username, @Value("${LENDIX_ADMIN_PASSWORD:}") String password) {
        if (password.isBlank()) throw new IllegalStateException("LENDIX_ADMIN_PASSWORD debe configurarse");
        return new MapReactiveUserDetailsService(User.withUsername(username).password(passwordEncoder().encode(password)).roles("ADMIN").build());
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
}

