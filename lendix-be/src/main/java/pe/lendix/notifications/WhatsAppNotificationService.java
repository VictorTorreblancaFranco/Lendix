package pe.lendix.notifications;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class WhatsAppNotificationService {
    private final DatabaseClient db;
    private final WebClient client;
    private final String apiUrl;
    private final String token;

    public WhatsAppNotificationService(DatabaseClient db, WebClient.Builder builder,
            @Value("${WHATSAPP_API_URL:}") String apiUrl,
            @Value("${WHATSAPP_API_TOKEN:}") String token) {
        this.db = db; this.client = builder.build(); this.apiUrl = apiUrl; this.token = token;
    }

    public Mono<Map<String,Object>> send(UUID notificationId, String recipient, String message) {
        if (apiUrl.isBlank() || token.isBlank()) return Mono.just(Map.of("status", "PENDING", "reason", "WhatsApp no configurado"));
        return client.post().uri(apiUrl).headers(h -> h.setBearerAuth(token)).bodyValue(Map.of("to",recipient,"message",message)).retrieve().toBodilessEntity()
                .flatMap(response -> db.sql("UPDATE notifications SET status='SENT',sent_at=now() WHERE id=:id RETURNING id,status,sent_at").bind("id",notificationId).fetch().one())
                .onErrorResume(error -> db.sql("UPDATE notifications SET status='FAILED' WHERE id=:id RETURNING id,status").bind("id",notificationId).fetch().one());
    }
}
