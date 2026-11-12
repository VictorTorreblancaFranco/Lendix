package pe.lendix.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class WebConfig implements WebFluxConfigurer {
    @Value("${LENDIX_FRONTEND_ORIGIN:http://localhost:4200}") private String frontendOrigin;
    @Bean WebClient.Builder webClientBuilder() { return WebClient.builder(); }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("http://localhost:4200", frontendOrigin).allowedMethods("GET","POST","PUT","DELETE","OPTIONS").allowCredentials(true);
    }
}
