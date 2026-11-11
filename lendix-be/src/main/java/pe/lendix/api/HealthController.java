package pe.lendix.api;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
@RestController public class HealthController { @GetMapping("/api/v1/health") public Mono<Map<String,Object>> health() { return Mono.just(Map.of("service","lendix-be","status","UP","timestamp",OffsetDateTime.now(ZoneOffset.UTC))); } }

