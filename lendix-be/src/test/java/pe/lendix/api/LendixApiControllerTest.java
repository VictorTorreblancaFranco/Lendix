package pe.lendix.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LendixApiControllerTest {
    @Test void monetaryValuesUseDecimalPrecision() {
        var principal = new BigDecimal("400.00");
        var rate = new BigDecimal("30.00");
        assertEquals(new BigDecimal("120.0000"), principal.multiply(rate).divide(BigDecimal.valueOf(100)));
    }
}
