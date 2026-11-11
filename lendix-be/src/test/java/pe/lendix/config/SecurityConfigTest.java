package pe.lendix.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SecurityConfigTest {
    @Test void passwordIsVerifiedWithBcrypt() {
        var encoder = new BCryptPasswordEncoder();
        var hash = encoder.encode("strong-test-password");
        assertTrue(encoder.matches("strong-test-password", hash));
    }
}
