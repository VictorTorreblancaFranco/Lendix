package pe.lendix.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebConfig implements WebFluxConfigurer {
    @Bean WebClient.Builder webClientBuilder() { return WebClient.builder(); }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("http://localhost:4200").allowedMethods("GET","POST","PUT","DELETE","OPTIONS").allowCredentials(true);
    }
}
