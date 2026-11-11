package pe.lendix.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1")
public class LendixApiController {
    private final DatabaseClient db;
    public LendixApiController(DatabaseClient db) { this.db = db; }

    @GetMapping("/customers")
    public Flux<Map<String,Object>> customers(@RequestParam(defaultValue = "") String q) {
        return db.sql("SELECT id, full_name, dni, phone, email, active FROM customers WHERE active = true AND (lower(full_name) LIKE lower(:q) OR dni LIKE :q OR phone LIKE :q) ORDER BY full_name")
                .bind("q", "%" + q.trim() + "%").fetch().all();
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String,Object>> createCustomer(@RequestBody CustomerRequest request) {
        if (request.fullName() == null || request.fullName().isBlank() || request.dni() == null || !request.dni().matches("\\d{8}") || request.phone() == null || request.phone().isBlank())
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre, DNI válido y teléfono son obligatorios"));
        return db.sql("INSERT INTO customers(full_name,dni,phone,email,address,notes) VALUES(:name,:dni,:phone,:email,:address,:notes) RETURNING id,full_name,dni,phone,email,active")
                .bind("name", request.fullName().trim()).bind("dni", request.dni()).bind("phone", request.phone().trim())
                .bind("email", request.email()).bind("address", request.address()).bind("notes", request.notes()).fetch().one();
    }

    @PutMapping("/customers/{id}")
    public Mono<Map<String,Object>> updateCustomer(@PathVariable UUID id, @RequestBody CustomerRequest request) {
        return db.sql("UPDATE customers SET full_name=:name, phone=:phone, email=:email, address=:address, notes=:notes, updated_at=now() WHERE id=:id RETURNING id,full_name,dni,phone,email,active")
                .bind("id", id).bind("name", request.fullName()).bind("phone", request.phone()).bind("email", request.email()).bind("address", request.address()).bind("notes", request.notes()).fetch().one();
    }

    @DeleteMapping("/customers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deactivateCustomer(@PathVariable UUID id) {
        return db.sql("UPDATE customers SET active=false, updated_at=now() WHERE id=:id").bind("id", id).fetch().rowsUpdated().then();
    }

    @GetMapping("/loans")
    public Flux<Map<String,Object>> loans(@RequestParam(defaultValue = "") String status) {
        String sql = "SELECT l.id,l.customer_id,c.full_name,l.principal,l.interest_rate,l.rate_period,l.term_count,l.payment_frequency,l.disbursement_date,l.status,l.total_due,l.outstanding_principal FROM loans l JOIN customers c ON c.id=l.customer_id " + (status.isBlank() ? "" : "WHERE l.status=:status ") + "ORDER BY l.created_at DESC";
        var spec = db.sql(sql); if (!status.isBlank()) spec = spec.bind("status", status); return spec.fetch().all();
    }

    @PostMapping("/loans")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String,Object>> createLoan(@RequestBody LoanRequest r) {
        if (r.customerId() == null || r.principal() == null || r.principal().signum() <= 0 || r.interestRate() == null || r.termCount() == null || r.termCount() < 1 || r.termCount() > 365)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Condiciones del préstamo inválidas"));
        BigDecimal interest = r.principal().multiply(r.interestRate()).divide(BigDecimal.valueOf(100));
        BigDecimal total = r.principal().add(interest.multiply(BigDecimal.valueOf(r.termCount())));
        return db.sql("INSERT INTO loans(customer_id,principal,interest_rate,rate_period,term_count,payment_frequency,disbursement_date,first_due_date,total_due,outstanding_principal,notes) VALUES(:customer,:principal,:rate,:period,:terms,:frequency,:disbursement,:firstDue,:total,:principal,:notes) RETURNING *")
                .bind("customer", r.customerId()).bind("principal", r.principal()).bind("rate", r.interestRate()).bind("period", r.ratePeriod()).bind("terms", r.termCount()).bind("frequency", r.paymentFrequency()).bind("disbursement", r.disbursementDate()).bind("firstDue", r.firstDueDate()).bind("total", total).bind("notes", r.notes()).fetch().one()
                .flatMap(loan -> createInstallments(loan, r, interest).then(db.sql("INSERT INTO audit_log(action,entity_type,entity_id,details) VALUES('CREATE','LOAN',:id,:details)").bind("id", loan.get("id")).bind("details", "Préstamo creado").fetch().rowsUpdated()).thenReturn(loan));
    }

    private Mono<Void> createInstallments(Map<String,Object> loan, LoanRequest r, BigDecimal interest) {
        BigDecimal principalPart = r.principal().divide(BigDecimal.valueOf(r.termCount()), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal interestPart = interest.divide(BigDecimal.valueOf(r.termCount()), 2, java.math.RoundingMode.HALF_UP);
        return Flux.range(1, r.termCount()).concatMap(n -> db.sql("INSERT INTO installments(loan_id,installment_number,due_date,principal_due,interest_due,amount_due) VALUES(:loan,:n,:due,:principal,:interest,:amount)")
                .bind("loan", loan.get("id")).bind("n", n).bind("due", r.firstDueDate().plusDays(periodDays(r.paymentFrequency()) * (long)(n - 1))).bind("principal", principalPart).bind("interest", interestPart).bind("amount", principalPart.add(interestPart)).fetch().rowsUpdated()).then();
    }
    private long periodDays(String frequency) { return switch (frequency == null ? "MONTHLY" : frequency.toUpperCase()) { case "DAILY" -> 1; case "WEEKLY" -> 7; case "BIWEEKLY" -> 14; default -> 30; }; }

    @GetMapping("/loans/{id}/installments")
    public Flux<Map<String,Object>> installments(@PathVariable UUID id) { return db.sql("SELECT * FROM installments WHERE loan_id=:id ORDER BY installment_number").bind("id", id).fetch().all(); }

    @PostMapping("/loans/{id}/extension")
    public Mono<Map<String,Object>> extend(@PathVariable UUID id, @RequestBody ExtensionRequest r) { return db.sql("INSERT INTO loan_events(loan_id,event_type,previous_due_date,new_due_date,charge,notes) SELECT id,'EXTENSION',first_due_date,:newDate,:charge,:notes FROM loans WHERE id=:id RETURNING *").bind("id",id).bind("newDate",r.newDueDate()).bind("charge",r.charge()==null?BigDecimal.ZERO:r.charge()).bind("notes",r.notes()).fetch().one(); }

    @GetMapping("/cash/movements")
    public Flux<Map<String,Object>> cashMovements() { return db.sql("SELECT * FROM cash_movements ORDER BY created_at DESC").fetch().all(); }
    @PostMapping("/cash/movements")
    public Mono<Map<String,Object>> cashMovement(@RequestBody CashRequest r) { if(r.amount()==null||r.amount().signum()<=0)return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,"El importe debe ser positivo")); return db.sql("INSERT INTO cash_movements(movement_type,amount,source,reference_id,notes) VALUES(:type,:amount,:source,:reference,:notes) RETURNING *").bind("type",r.movementType()).bind("amount",r.amount()).bind("source",r.source()).bind("reference",r.referenceId()).bind("notes",r.notes()).fetch().one(); }

    @PostMapping("/loans/{id}/payments")
    public Mono<Map<String,Object>> registerPayment(@PathVariable UUID id, @RequestBody PaymentRequest r) {
        if (r.amount() == null || r.amount().signum() <= 0) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "El pago debe ser positivo"));
        return db.sql("SELECT id,outstanding_principal,interest_rate FROM loans WHERE id=:id FOR UPDATE").bind("id", id).fetch().one()
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Préstamo no encontrado")))
                .flatMap(loan -> { BigDecimal principal = (BigDecimal) loan.get("outstanding_principal"); BigDecimal rate = (BigDecimal) loan.get("interest_rate"); BigDecimal interestDue = principal.multiply(rate).divide(BigDecimal.valueOf(100)); BigDecimal interestPaid = r.amount().min(interestDue); BigDecimal principalPaid = r.amount().subtract(interestPaid).min(principal); BigDecimal newPrincipal = principal.subtract(principalPaid); return db.sql("INSERT INTO payments(loan_id,paid_at,amount,principal_paid,interest_paid,payment_method,notes) VALUES(:loan,:paidAt,:amount,:principalPaid,:interestPaid,:method,:notes) RETURNING *").bind("loan",id).bind("paidAt",r.paidAt()==null?LocalDate.now():r.paidAt()).bind("amount",r.amount()).bind("principalPaid",principalPaid).bind("interestPaid",interestPaid).bind("method",r.paymentMethod()).bind("notes",r.notes()).fetch().one().flatMap(payment -> db.sql("UPDATE loans SET outstanding_principal=:principal,status=CASE WHEN :principal=0 THEN 'PAID' ELSE 'ACTIVE' END, updated_at=now() WHERE id=:id").bind("principal",newPrincipal).bind("id",id).fetch().rowsUpdated().thenReturn(payment)); });
    }

    @GetMapping("/dashboard")
    public Mono<Map<String,Object>> dashboard() { return db.sql("SELECT COUNT(*) FILTER (WHERE active) AS active_customers, COALESCE((SELECT SUM(principal) FROM loans WHERE status IN ('ACTIVE','OVERDUE')),0) AS capital_placed, COALESCE((SELECT SUM(outstanding_principal) FROM loans WHERE status IN ('ACTIVE','OVERDUE')),0) AS outstanding, COUNT(*) FILTER (WHERE status='OVERDUE') AS overdue_loans FROM customers c LEFT JOIN loans l ON false").fetch().one(); }

    public record CustomerRequest(String fullName, String dni, String phone, String email, String address, String notes) {}
    public record LoanRequest(UUID customerId, BigDecimal principal, BigDecimal interestRate, String ratePeriod, Integer termCount, String paymentFrequency, LocalDate disbursementDate, LocalDate firstDueDate, String notes) {}
    public record PaymentRequest(BigDecimal amount, LocalDate paidAt, String paymentMethod, String notes) {}
    public record ExtensionRequest(LocalDate newDueDate, BigDecimal charge, String notes) {}
    public record CashRequest(String movementType, BigDecimal amount, String source, UUID referenceId, String notes) {}
}

