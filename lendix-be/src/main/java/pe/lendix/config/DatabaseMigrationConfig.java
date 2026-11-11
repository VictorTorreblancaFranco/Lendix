package pe.lendix.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseMigrationConfig {
    @Bean
    CommandLineRunner migrateDatabase(
            @Value("${spring.flyway.url}") String url,
            @Value("${spring.flyway.user}") String user,
            @Value("${spring.flyway.password}") String password) {
        return args -> Flyway.configure().dataSource(url, user, password).baselineOnMigrate(true).load().migrate();
    }
}
