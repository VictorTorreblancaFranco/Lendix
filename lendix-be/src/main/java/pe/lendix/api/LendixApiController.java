package pe.lendix.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.http.MediaType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import java.io.ByteArrayOutputStream;
import pe.lendix.notifications.WhatsAppNotificationService;
import org.springframework.transaction.reactive.TransactionalOperator;

@RestController
@RequestMapping("/api/v1")
public class LendixApiController {
    private final DatabaseClient db;
    private final WhatsAppNotificationService whatsapp;
    private final TransactionalOperator tx;
    public LendixApiController(DatabaseClient db, WhatsAppNotificationService whatsapp, TransactionalOperator tx) { this.db = db; this.whatsapp = whatsapp; this.tx = tx; }

    @GetMapping("/customers")
    public Flux<Map<String,Object>> customers(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "false") boolean includeInactive) {
        String sql = "SELECT id, full_name, dni, phone, email, active FROM customers WHERE " + (includeInactive ? "1=1" : "active = true") + " AND (lower(full_name) LIKE lower(:q) OR dni LIKE :q OR phone LIKE :q) ORDER BY full_name";
        return db.sql(sql).bind("q", "%" + q.trim() + "%").fetch().all();
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String,Object>> createCustomer(@RequestBody CustomerRequest request) {
        if (request.fullName() == null || request.fullName().isBlank() || request.dni() == null || !request.dni().matches("\\d{8}") || request.phone() == null || request.phone().isBlank())
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre, DNI válido y teléfono son obligatorios"));
        var spec = db.sql("INSERT INTO customers(full_name,dni,phone,email,address,notes) VALUES(:name,:dni,:phone,:email,:address,:notes) RETURNING id,full_name,dni,phone,email,active")
                .bind("name", request.fullName().trim()).bind("dni", request.dni()).bind("phone", request.phone().trim());
        spec = request.email() == null ? spec.bindNull("email", String.class) : spec.bind("email", request.email());
        spec = request.address() == null ? spec.bindNull("address", String.class) : spec.bind("address", request.address());
        spec = request.notes() == null ? spec.bindNull("notes", String.class) : spec.bind("notes", request.notes());
        return spec.fetch().one();
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

    @PostMapping("/customers/{id}/restore")
    public Mono<Map<String,Object>> restoreCustomer(@PathVariable UUID id) { return db.sql("UPDATE customers SET active=true, updated_at=now() WHERE id=:id RETURNING id,full_name,dni,phone,email,active").bind("id",id).fetch().one(); }

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
        String ratePeriod = r.ratePeriod() == null ? "MONTHLY" : r.ratePeriod();
        String frequency = r.paymentFrequency() == null ? "MONTHLY" : r.paymentFrequency();
        LocalDate disbursement = r.disbursementDate() == null ? LocalDate.now() : r.disbursementDate();
        if (r.firstDueDate() == null) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica la primera fecha de pago"));
        BigDecimal periodFactor = switch (frequency.toUpperCase()) { case "WEEKLY" -> BigDecimal.valueOf(0.25); case "BIWEEKLY" -> BigDecimal.valueOf(0.5); case "DAILY" -> BigDecimal.valueOf(1).divide(BigDecimal.valueOf(30), 6, java.math.RoundingMode.HALF_UP); default -> BigDecimal.ONE; };
        BigDecimal interest = r.principal().multiply(r.interestRate()).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP).multiply(periodFactor).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal total = r.principal().add(interest.multiply(BigDecimal.valueOf(r.termCount())));
        String requestKey = r.requestId() == null || r.requestId().isBlank() ? UUID.randomUUID().toString() : r.requestId();
        return db.sql("SELECT * FROM loans WHERE idempotency_key=:key").bind("key", requestKey).fetch().one()
                .switchIfEmpty(db.sql("INSERT INTO loans(customer_id,principal,interest_rate,rate_period,term_count,payment_frequency,disbursement_date,first_due_date,total_due,outstanding_principal,notes,idempotency_key) VALUES(:customer,:principal,:rate,:period,:terms,:frequency,:disbursement,:firstDue,:total,:principal,:notes,:key) RETURNING *")
                .bind("customer", r.customerId()).bind("principal", r.principal()).bind("rate", r.interestRate()).bind("period", ratePeriod).bind("terms", r.termCount()).bind("frequency", frequency).bind("disbursement", disbursement).bind("firstDue", r.firstDueDate()).bind("total", total).bind("notes", r.notes() == null ? "" : r.notes()).bind("key", requestKey).fetch().one())
                .flatMap(loan -> db.sql("SELECT COUNT(*) AS total FROM installments WHERE loan_id=:loan").bind("loan", loan.get("id")).fetch().one().flatMap(existing -> {
                    Number count = (Number) existing.get("total");
                    if (count != null && count.longValue() > 0) return Mono.just(loan);
                    return createInstallments(loan, r, interest).then(db.sql("INSERT INTO audit_log(action,entity_type,entity_id,details) VALUES('CREATE','LOAN',:id,:details)").bind("id", loan.get("id")).bind("details", "Préstamo creado").fetch().rowsUpdated()).thenReturn(loan);
                }));
    }

    private Mono<Void> createInstallments(Map<String,Object> loan, LoanRequest r, BigDecimal interest) {
        BigDecimal principalPart = r.principal().divide(BigDecimal.valueOf(r.termCount()), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal interestPart = interest;
        return Flux.range(1, r.termCount()).concatMap(n -> db.sql("INSERT INTO installments(loan_id,installment_number,due_date,principal_due,interest_due,amount_due) VALUES(:loan,:n,:due,:principal,:interest,:amount)")
                .bind("loan", loan.get("id")).bind("n", n).bind("due", r.firstDueDate().plusDays(periodDays(r.paymentFrequency()) * (long)(n - 1))).bind("principal", principalPart).bind("interest", interestPart).bind("amount", principalPart.add(interestPart)).fetch().rowsUpdated()).then();
    }
    private long periodDays(String frequency) { return switch (frequency == null ? "MONTHLY" : frequency.toUpperCase()) { case "DAILY" -> 1; case "WEEKLY" -> 7; case "BIWEEKLY" -> 14; default -> 30; }; }

    @GetMapping("/loans/{id}/installments")
    public Flux<Map<String,Object>> installments(@PathVariable UUID id) { return db.sql("SELECT * FROM installments WHERE loan_id=:id ORDER BY installment_number").bind("id", id).fetch().all(); }

    @GetMapping("/loans/{id}/payments")
    public Flux<Map<String,Object>> payments(@PathVariable UUID id) { return db.sql("SELECT * FROM payments WHERE loan_id=:id ORDER BY paid_at DESC, created_at DESC").bind("id", id).fetch().all(); }

    @GetMapping("/payments")
    public Flux<Map<String,Object>> allPayments(@RequestParam(defaultValue = "") String customerId) {
        String sql = "SELECT p.id,p.loan_id,p.installment_id,p.paid_at,p.amount,p.principal_paid,p.interest_paid,p.payment_method,p.notes,p.created_at,p.reversed_at,p.reversal_reason,c.id AS customer_id,c.full_name,c.dni FROM payments p JOIN loans l ON l.id=p.loan_id JOIN customers c ON c.id=l.customer_id "
                + (customerId.isBlank() ? "" : "WHERE c.id=:customerId ") + "ORDER BY p.paid_at DESC,p.created_at DESC";
        var spec = db.sql(sql); if (!customerId.isBlank()) spec = spec.bind("customerId", UUID.fromString(customerId)); return spec.fetch().all();
    }

    @GetMapping("/customers/{id}/payments")
    public Flux<Map<String,Object>> customerPayments(@PathVariable UUID id) { return allPayments(id.toString()); }

    @PostMapping("/payments/{id}/reverse")
    public Mono<Map<String,Object>> reversePayment(@PathVariable UUID id, @RequestBody ReversePaymentRequest request) {
        String reason = request.reason() == null || request.reason().isBlank() ? "Corrección autorizada" : request.reason().trim();
        Mono<Map<String,Object>> flow = db.sql("SELECT * FROM payments WHERE id=:id AND reversed_at IS NULL FOR UPDATE").bind("id", id).fetch().one()
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "El pago no existe o ya fue revertido")))
                .flatMap(payment -> db.sql("SELECT * FROM payment_allocations WHERE payment_id=:payment ORDER BY created_at").bind("payment", id).fetch().all().collectList()
                    .flatMap(allocations -> Flux.fromIterable(allocations).concatMap(a -> db.sql("UPDATE installments SET amount_paid=GREATEST(0,amount_paid-:amount),principal_paid=GREATEST(0,principal_paid-:principal),interest_paid=GREATEST(0,interest_paid-:interest),status=CASE WHEN amount_paid-:amount<=0 THEN 'PENDING' ELSE 'PARTIAL' END WHERE id=:installment")
                            .bind("amount", a.get("amount")).bind("principal", a.get("principal_paid")).bind("interest", a.get("interest_paid")).bind("installment", a.get("installment_id")).fetch().rowsUpdated()).then()
                        .then(db.sql("UPDATE payments SET reversed_at=now(),reversal_reason=:reason WHERE id=:id").bind("reason", reason).bind("id", id).fetch().rowsUpdated())
                        .then(db.sql("UPDATE loans SET outstanding_principal=outstanding_principal+:principal,status='ACTIVE',updated_at=now(),version=version+1 WHERE id=:loan").bind("principal", payment.get("principal_paid")).bind("loan", payment.get("loan_id")).fetch().rowsUpdated())
                        .then(db.sql("INSERT INTO audit_log(action,entity_type,entity_id,details) VALUES('REVERSE','PAYMENT',:id,:details)").bind("id", id).bind("details", reason).fetch().rowsUpdated())
                        .thenReturn(Map.of("id", id, "status", "REVERSED", "reason", reason))));
        return tx.transactional(flow);
    }

    @GetMapping("/notifications/upcoming")
    public Flux<Map<String,Object>> upcomingNotifications() { return db.sql("SELECT i.id,i.installment_number,i.due_date,i.amount_due,i.amount_paid,c.full_name,c.phone,l.id AS loan_id,CASE WHEN i.due_date<CURRENT_DATE THEN 'OVERDUE' ELSE 'UPCOMING' END AS alert_type FROM installments i JOIN loans l ON l.id=i.loan_id JOIN customers c ON c.id=l.customer_id WHERE l.status IN ('ACTIVE','OVERDUE') AND i.status IN ('PENDING','PARTIAL') AND i.due_date<=CURRENT_DATE+1 ORDER BY i.due_date,c.full_name").fetch().all(); }

    @PostMapping("/notifications/prepare-daily")
    public Mono<Map<String,Object>> prepareDailyNotifications() {
        String message = "Recordatorio Lendix: la cuota de {{full_name}} vence mañana por S/ {{amount}}.";
        return db.sql("INSERT INTO notifications(channel,recipient,message,reference_id,kind) SELECT 'IN_APP',c.phone,:message,i.id,'PAYMENT_REMINDER' FROM installments i JOIN loans l ON l.id=i.loan_id JOIN customers c ON c.id=l.customer_id WHERE l.status IN ('ACTIVE','OVERDUE') AND i.status IN ('PENDING','PARTIAL') AND i.due_date=CURRENT_DATE+1 ON CONFLICT DO NOTHING")
                .bind("message", message).fetch().rowsUpdated().map(count -> Map.of("created", count, "date", LocalDate.now()));
    }

    @GetMapping("/loans/{id}/early-settlement")
    public Mono<Map<String,Object>> earlySettlement(@PathVariable UUID id) { return db.sql("SELECT id,outstanding_principal,interest_rate FROM loans WHERE id=:id").bind("id",id).fetch().one().map(l -> { BigDecimal principal=(BigDecimal)l.get("outstanding_principal"); BigDecimal rate=(BigDecimal)l.get("interest_rate"); BigDecimal interest=principal.multiply(rate).divide(BigDecimal.valueOf(100)); return Map.of("loanId",id,"principal",principal,"estimatedInterest",interest,"estimatedTotal",principal.add(interest)); }); }

    @PostMapping("/loans/{id}/early-settlement")
    public Mono<Map<String,Object>> settleLoan(@PathVariable UUID id) { return db.sql("UPDATE loans SET outstanding_principal=0,status='PAID',updated_at=now() WHERE id=:id AND status IN ('ACTIVE','OVERDUE') RETURNING id,status,outstanding_principal").bind("id",id).fetch().one().switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"El préstamo ya está cerrado o no existe"))); }

    @PostMapping("/loans/{id}/cancel")
    public Mono<Map<String,Object>> cancelLoan(@PathVariable UUID id) { return db.sql("UPDATE loans SET status='CANCELLED',updated_at=now() WHERE id=:id AND status IN ('ACTIVE','OVERDUE') RETURNING id,status,outstanding_principal").bind("id",id).fetch().one().switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"El préstamo ya está cerrado o no existe"))); }

    @PostMapping("/loans/{id}/renew")
    public Mono<Map<String,Object>> renew(@PathVariable UUID id, @RequestBody RenewalRequest r) { return db.sql("INSERT INTO loan_events(loan_id,event_type,previous_due_date,new_due_date,charge,notes) SELECT id,'RENEWAL',first_due_date,:newDate,:charge,:notes FROM loans WHERE id=:id RETURNING *").bind("id",id).bind("newDate",r.newDueDate()).bind("charge",r.charge()==null?BigDecimal.ZERO:r.charge()).bind("notes",r.notes()).fetch().one().flatMap(event -> db.sql("UPDATE loans SET status='ACTIVE', first_due_date=:newDate, updated_at=now() WHERE id=:id").bind("id",id).bind("newDate",r.newDueDate()).fetch().rowsUpdated().thenReturn(event)); }

    @GetMapping(value="/reports/portfolio", produces="text/csv")
    public Flux<String> portfolioReport() { return db.sql("SELECT c.full_name,c.dni,l.principal,l.outstanding_principal,l.status FROM loans l JOIN customers c ON c.id=l.customer_id ORDER BY c.full_name").fetch().all().map(r -> String.format("%s,%s,%s,%s,%s",r.get("full_name"),r.get("dni"),r.get("principal"),r.get("outstanding_principal"),r.get("status"))).startWith("cliente,dni,capital,saldo_pendiente,estado\n"); }

    @GetMapping(value="/loans/{id}/receipt", produces=MediaType.APPLICATION_PDF_VALUE)
    public Mono<byte[]> receipt(@PathVariable UUID id) { return db.sql("SELECT l.id,c.full_name,c.dni,l.principal,l.total_due,l.outstanding_principal,l.status FROM loans l JOIN customers c ON c.id=l.customer_id WHERE l.id=:id").bind("id",id).fetch().one().switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Préstamo no encontrado"))).map(this::pdf); }

    @GetMapping(value="/loans/{id}/report.pdf", produces=MediaType.APPLICATION_PDF_VALUE)
    public Mono<byte[]> loanReport(@PathVariable UUID id) {
        Mono<Map<String,Object>> loan = db.sql("SELECT l.id,c.full_name,c.dni,l.principal,l.total_due,l.outstanding_principal,l.status,l.term_count FROM loans l JOIN customers c ON c.id=l.customer_id WHERE l.id=:id").bind("id",id).fetch().one();
        Mono<java.util.List<Map<String,Object>>> installments = db.sql("SELECT installment_number,due_date,amount_due,amount_paid,status FROM installments WHERE loan_id=:id ORDER BY installment_number").bind("id",id).fetch().all().collectList();
        Mono<java.util.List<Map<String,Object>>> payments = db.sql("SELECT paid_at,amount,payment_method,notes FROM payments WHERE loan_id=:id ORDER BY paid_at,created_at").bind("id",id).fetch().all().collectList();
        return Mono.zip(loan, installments, payments).map(t -> loanReportBytes(t.getT1(), t.getT2(), t.getT3())).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Préstamo no encontrado")));
    }

    @GetMapping(value="/reports/portfolio.pdf", produces=MediaType.APPLICATION_PDF_VALUE)
    public Mono<byte[]> portfolioPdf() {
        String sql = "SELECT c.full_name,c.dni,l.principal,l.total_due,l.outstanding_principal,l.status,l.term_count, "
                + "COUNT(i.id) AS total_installments, COUNT(i.id) FILTER (WHERE i.status='PAID') AS paid_installments, "
                + "COUNT(i.id) FILTER (WHERE i.status='PARTIAL') AS partial_installments, "
                + "COUNT(i.id) FILTER (WHERE i.status IN ('PENDING','PARTIAL')) AS pending_installments "
                + "FROM loans l JOIN customers c ON c.id=l.customer_id LEFT JOIN installments i ON i.loan_id=l.id "
                + "GROUP BY c.full_name,c.dni,l.id ORDER BY c.full_name,l.created_at DESC";
        return db.sql(sql).fetch().all().collectList().map(this::portfolioPdfBytes);
    }

    @GetMapping(value="/reports/payments.pdf", produces=MediaType.APPLICATION_PDF_VALUE)
    public Mono<byte[]> paymentsPdf() { return allPayments("").collectList().map(rows -> { var lines = new ArrayList<String>(); lines.add("LENDIX - Historial de pagos"); lines.add("Generado: " + LocalDate.now()); lines.add("Pagos registrados: " + rows.size()); rows.forEach(p -> lines.add(p.get("paid_at") + " | " + p.get("full_name") + " | S/ " + p.get("amount") + " | " + p.get("payment_method") + (p.get("reversed_at") == null ? "" : " | REVERTIDO"))); return simplePdf(lines.toArray(String[]::new)); }); }

    @GetMapping(value="/reports/customers/{id}.pdf", produces=MediaType.APPLICATION_PDF_VALUE)
    public Mono<byte[]> customerReport(@PathVariable UUID id) {
        Mono<Map<String,Object>> customer = db.sql("SELECT id,full_name,dni,phone,email FROM customers WHERE id=:id").bind("id", id).fetch().one();
        Mono<List<Map<String,Object>>> loans = db.sql("SELECT id,principal,total_due,outstanding_principal,status,term_count FROM loans WHERE customer_id=:id ORDER BY created_at DESC").bind("id", id).fetch().all().collectList();
        Mono<List<Map<String,Object>>> payments = allPayments(id.toString()).collectList();
        return Mono.zip(customer, loans, payments).map(t -> { var lines = new ArrayList<String>(); var c=t.getT1(); lines.add("LENDIX - Reporte del cliente"); lines.add("Cliente: "+c.get("full_name")+" | DNI: "+c.get("dni")); lines.add("Telefono: "+c.get("phone")); lines.add("PRESTAMOS"); t.getT2().forEach(l -> lines.add("Capital S/ "+l.get("principal")+" | Total S/ "+l.get("total_due")+" | Saldo S/ "+l.get("outstanding_principal")+" | "+l.get("status"))); lines.add("PAGOS"); t.getT3().forEach(p -> lines.add(p.get("paid_at")+" | S/ "+p.get("amount")+" | "+p.get("payment_method")+(p.get("reversed_at")==null?"":" | REVERTIDO"))); return simplePdf(lines.toArray(String[]::new)); }).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Cliente no encontrado")));
    }

    @GetMapping(value="/payments/{id}/receipt", produces=MediaType.APPLICATION_PDF_VALUE)
    public Mono<byte[]> paymentReceipt(@PathVariable UUID id) { return db.sql("SELECT p.id,p.amount,p.paid_at,p.payment_method,c.full_name,c.dni,l.id AS loan_id FROM payments p JOIN loans l ON l.id=p.loan_id JOIN customers c ON c.id=l.customer_id WHERE p.id=:id").bind("id", id).fetch().one().switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Pago no encontrado"))).map(this::paymentPdf); }

    @PostMapping("/notifications/whatsapp")
    public Mono<Map<String,Object>> queueWhatsapp(@RequestBody NotificationRequest r) { var spec=db.sql("INSERT INTO notifications(channel,recipient,message,reference_id) VALUES('WHATSAPP',:recipient,:message,:reference) RETURNING *").bind("recipient",r.recipient()).bind("message",r.message()); spec=r.referenceId()==null?spec.bindNull("reference",UUID.class):spec.bind("reference",r.referenceId()); return spec.fetch().one(); }

    @PostMapping("/notifications/whatsapp/{id}/send")
    public Mono<Map<String,Object>> sendWhatsapp(@PathVariable UUID id) { return db.sql("SELECT id,recipient,message FROM notifications WHERE id=:id").bind("id",id).fetch().one().switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Notificación no encontrada"))).flatMap(n -> whatsapp.send(id,(String)n.get("recipient"),(String)n.get("message"))); }

    private byte[] pdf(Map<String,Object> row) { try (var doc=new PDDocument(); var out=new ByteArrayOutputStream()) { var page=new PDPage(); doc.addPage(page); try(var cs=new PDPageContentStream(doc,page)){ cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),18); cs.newLineAtOffset(60,730); cs.showText("LENDIX - Comprobante de préstamo"); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12); cs.newLineAtOffset(0,-35); cs.showText("Cliente: "+row.get("full_name")); cs.newLineAtOffset(0,-22); cs.showText("DNI: "+row.get("dni")); cs.newLineAtOffset(0,-22); cs.showText("Capital: S/ "+row.get("principal")); cs.newLineAtOffset(0,-22); cs.showText("Total programado: S/ "+row.get("total_due")); cs.newLineAtOffset(0,-22); cs.showText("Saldo de capital: S/ "+row.get("outstanding_principal")); cs.newLineAtOffset(0,-22); cs.showText("Estado: "+row.get("status")); cs.endText(); } doc.save(out); return out.toByteArray(); } catch(Exception e){ throw new IllegalStateException("No se pudo generar el comprobante",e); } }

    private byte[] paymentPdf(Map<String,Object> row) { return simplePdf("LENDIX - Voucher de pago", "Cliente: "+row.get("full_name"), "DNI: "+row.get("dni"), "Importe: S/ "+row.get("amount"), "Fecha: "+row.get("paid_at"), "Método: "+row.get("payment_method")); }
    private byte[] loanReportBytes(Map<String,Object> loan, java.util.List<Map<String,Object>> installments, java.util.List<Map<String,Object>> payments) {
        var lines = new java.util.ArrayList<String>(); lines.add("LENDIX - Reporte del prestamo"); lines.add("Cliente: "+loan.get("full_name")+" | DNI: "+loan.get("dni")); lines.add("Capital: S/ "+loan.get("principal")+" | Total: S/ "+loan.get("total_due")+" | Saldo: S/ "+loan.get("outstanding_principal")); lines.add("Estado: "+loan.get("status")); lines.add("CUOTAS"); installments.forEach(i -> lines.add("Cuota "+i.get("installment_number")+" | "+i.get("due_date")+" | S/ "+i.get("amount_due")+" | Pagado S/ "+i.get("amount_paid")+" | "+i.get("status"))); lines.add("PAGOS"); payments.forEach(p -> lines.add(p.get("paid_at")+" | S/ "+p.get("amount")+" | "+p.get("payment_method")+" | "+(p.get("notes")==null?"":p.get("notes")))); return simplePdf(lines.toArray(String[]::new));
    }
    private byte[] portfolioPdfBytes(java.util.List<Map<String,Object>> rows) {
        var lines = new java.util.ArrayList<String>();
        lines.add("LENDIX - Reporte de cartera");
        lines.add("Generado: " + LocalDate.now());
        lines.add("Prestamos registrados: " + rows.size());
        rows.forEach(r -> {
            lines.add("Cliente: " + r.get("full_name") + " | DNI: " + r.get("dni"));
            lines.add("Capital S/ " + r.get("principal") + " | Total S/ " + r.get("total_due") + " | Saldo S/ " + r.get("outstanding_principal"));
            lines.add("Cuotas: " + r.get("paid_installments") + " pagadas | " + r.get("partial_installments") + " parciales | " + r.get("pending_installments") + " pendientes | Estado: " + r.get("status"));
            lines.add("------------------------------------------------------------");
        });
        return simplePdf(lines.toArray(String[]::new));
    }
    private byte[] simplePdf(String... lines) { try (var doc=new PDDocument(); var out=new ByteArrayOutputStream()) {
        PDPage page = new PDPage(); doc.addPage(page); PDPageContentStream cs = new PDPageContentStream(doc, page); cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18); cs.newLineAtOffset(52, 740); cs.showText(cleanPdf(lines.length == 0 ? "LENDIX" : lines[0])); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10); float y = 704;
        for (int index = 1; index < lines.length; index++) { String value = cleanPdf(lines[index]); if (value.isBlank()) value = " "; while (!value.isEmpty()) { if (y < 54) { cs.endText(); cs.close(); page = new PDPage(); doc.addPage(page); cs = new PDPageContentStream(doc, page); cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10); cs.newLineAtOffset(52, 740); y = 740; } int length = Math.min(108, value.length()); int split = value.lastIndexOf(' ', length); if (split < 30) split = length; cs.newLineAtOffset(0, y == 740 ? 0 : -16); cs.showText(value.substring(0, split)); value = value.substring(split).trim(); y -= 16; } }
        cs.endText(); cs.close(); doc.save(out); return out.toByteArray(); } catch(Exception e){ throw new IllegalStateException("No se pudo generar el PDF",e); } }
    private String cleanPdf(String value) { return String.valueOf(value == null ? "" : value).replaceAll("[^\\x20-\\x7E]", " "); }

    @PostMapping("/loans/{id}/extension")
    public Mono<Map<String,Object>> extend(@PathVariable UUID id, @RequestBody ExtensionRequest r) { return db.sql("INSERT INTO loan_events(loan_id,event_type,previous_due_date,new_due_date,charge,notes) SELECT id,'EXTENSION',first_due_date,:newDate,:charge,:notes FROM loans WHERE id=:id RETURNING *").bind("id",id).bind("newDate",r.newDueDate()).bind("charge",r.charge()==null?BigDecimal.ZERO:r.charge()).bind("notes",r.notes()).fetch().one(); }

    @GetMapping("/cash/movements")
    public Flux<Map<String,Object>> cashMovements() { return db.sql("SELECT * FROM cash_movements ORDER BY created_at DESC").fetch().all(); }
    @PostMapping("/cash/movements")
    public Mono<Map<String,Object>> cashMovement(@RequestBody CashRequest r) { if(r.amount()==null||r.amount().signum()<=0)return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,"El importe debe ser positivo")); return db.sql("INSERT INTO cash_movements(movement_type,amount,source,reference_id,notes) VALUES(:type,:amount,:source,:reference,:notes) RETURNING *").bind("type",r.movementType()).bind("amount",r.amount()).bind("source",r.source()).bind("reference",r.referenceId()).bind("notes",r.notes()).fetch().one(); }

    @PostMapping("/loans/{id}/payments")
    public Mono<Map<String,Object>> registerPayment(@PathVariable UUID id, @RequestBody PaymentRequest r) {
        if (r.amount() == null || r.amount().signum() <= 0 || r.amount().stripTrailingZeros().scale() > 0)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "El pago debe ser un monto entero positivo"));
        String method = r.paymentMethod() == null || r.paymentMethod().isBlank() ? "CASH" : r.paymentMethod();
        String requestKey = r.requestId() == null || r.requestId().isBlank() ? UUID.randomUUID().toString() : r.requestId();
        Mono<Map<String,Object>> flow = db.sql("SELECT * FROM payments WHERE idempotency_key=:key").bind("key", requestKey).fetch().one()
                .switchIfEmpty(db.sql("SELECT id,outstanding_principal FROM loans WHERE id=:id AND status IN ('ACTIVE','OVERDUE') FOR UPDATE").bind("id", id).fetch().one()
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "El préstamo no existe o ya está cerrado")))
                    .flatMap(loan -> db.sql("SELECT id,installment_number,amount_due,amount_paid,principal_due,interest_due,principal_paid,interest_paid,status FROM installments WHERE loan_id=:loan ORDER BY installment_number FOR UPDATE").bind("loan", id).fetch().all().collectList()
                        .flatMap(items -> allocatePayment(id, loan, items, r, method, requestKey))));
        return tx.transactional(flow);
    }

    private Mono<Map<String,Object>> allocatePayment(UUID loanId, Map<String,Object> loan, List<Map<String,Object>> items, PaymentRequest r, String method, String requestKey) {
        int start = 0;
        if (r.installmentId() != null) {
            boolean found = false;
            for (int index = 0; index < items.size(); index++) if (r.installmentId().equals(items.get(index).get("id"))) { start = index; found = true; break; }
            if (!found) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "La cuota seleccionada no pertenece al préstamo"));
        } else {
            while (start < items.size() && "PAID".equals(items.get(start).get("status"))) start++;
        }
        if (items.isEmpty() || start >= items.size()) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay cuotas pendientes para este préstamo"));
        BigDecimal remainingTotal = BigDecimal.ZERO;
        for (Map<String,Object> item : items) remainingTotal = remainingTotal.add(((BigDecimal)item.get("amount_due")).subtract((BigDecimal)item.get("amount_paid")));
        if (r.amount().compareTo(remainingTotal) > 0) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "El pago supera el saldo programado del préstamo"));

        List<Map<String,Object>> allocations = new ArrayList<>();
        BigDecimal remaining = r.amount(), principalPaid = BigDecimal.ZERO, interestPaid = BigDecimal.ZERO;
        for (int index = start; index < items.size() && remaining.signum() > 0; index++) {
            Map<String,Object> item = items.get(index);
            BigDecimal due = (BigDecimal)item.get("amount_due"), paid = (BigDecimal)item.get("amount_paid");
            BigDecimal installmentRemaining = due.subtract(paid).max(BigDecimal.ZERO);
            if (installmentRemaining.signum() == 0) continue;
            BigDecimal allocated = remaining.min(installmentRemaining);
            BigDecimal interestLeft = ((BigDecimal)item.get("interest_due")).subtract((BigDecimal)item.get("interest_paid")).max(BigDecimal.ZERO);
            BigDecimal itemInterest = allocated.min(interestLeft);
            BigDecimal itemPrincipal = allocated.subtract(itemInterest);
            Map<String,Object> allocation = new HashMap<>();
            allocation.put("installmentId", item.get("id")); allocation.put("amount", allocated); allocation.put("principal", itemPrincipal); allocation.put("interest", itemInterest);
            allocations.add(allocation); remaining = remaining.subtract(allocated); principalPaid = principalPaid.add(itemPrincipal); interestPaid = interestPaid.add(itemInterest);
        }
        if (remaining.signum() > 0) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo distribuir el pago entre las cuotas"));
        BigDecimal oldPrincipal = (BigDecimal)loan.get("outstanding_principal");
        BigDecimal newPrincipal = oldPrincipal.subtract(principalPaid).max(BigDecimal.ZERO);
        UUID firstInstallment = (UUID)allocations.get(0).get("installmentId");
        var insert = db.sql("INSERT INTO payments(loan_id,installment_id,idempotency_key,paid_at,amount,principal_paid,interest_paid,payment_method,notes) VALUES(:loan,:installment,:key,:paidAt,:amount,:principal,:interest,:method,:notes) RETURNING *")
                .bind("loan", loanId).bind("installment", firstInstallment).bind("key", requestKey).bind("paidAt", r.paidAt()==null?LocalDate.now():r.paidAt()).bind("amount", r.amount()).bind("principal", principalPaid).bind("interest", interestPaid).bind("method", method).bind("notes", r.notes()==null?"":r.notes());
        return insert.fetch().one().flatMap(payment -> {
            Mono<Void> updateAllocations = Flux.fromIterable(allocations).concatMap(a -> {
                Mono<Long> update = db.sql("UPDATE installments SET amount_paid=amount_paid+:amount,principal_paid=principal_paid+:principal,interest_paid=interest_paid+:interest,status=CASE WHEN amount_paid+:amount>=amount_due THEN 'PAID' ELSE 'PARTIAL' END WHERE id=:installment")
                        .bind("amount", a.get("amount")).bind("principal", a.get("principal")).bind("interest", a.get("interest")).bind("installment", a.get("installmentId")).fetch().rowsUpdated();
                return update.then(db.sql("INSERT INTO payment_allocations(payment_id,installment_id,amount,principal_paid,interest_paid) VALUES(:payment,:installment,:amount,:principal,:interest)").bind("payment", payment.get("id")).bind("installment", a.get("installmentId")).bind("amount", a.get("amount")).bind("principal", a.get("principal")).bind("interest", a.get("interest")).fetch().rowsUpdated());
            }).then();
            return updateAllocations
                .then(db.sql("UPDATE loans SET outstanding_principal=:principal,status=CASE WHEN NOT EXISTS(SELECT 1 FROM installments WHERE loan_id=:loan AND status<>'PAID') THEN 'PAID' ELSE 'ACTIVE' END,updated_at=now(),version=version+1 WHERE id=:loan").bind("principal", newPrincipal).bind("loan", loanId).fetch().rowsUpdated())
                .then(db.sql("INSERT INTO audit_log(action,entity_type,entity_id,details) VALUES('CREATE','PAYMENT',:id,:details)").bind("id", payment.get("id")).bind("details", "Pago registrado y distribuido entre " + allocations.size() + " cuota(s)").fetch().rowsUpdated()).thenReturn(payment);
        });
    }

    @GetMapping("/dashboard")
    public Mono<Map<String,Object>> dashboard() { return db.sql("SELECT COUNT(*) FILTER (WHERE active) AS active_customers, COALESCE((SELECT SUM(principal) FROM loans WHERE status IN ('ACTIVE','OVERDUE')),0) AS capital_placed, COALESCE((SELECT SUM(outstanding_principal) FROM loans WHERE status IN ('ACTIVE','OVERDUE')),0) AS outstanding, COUNT(*) FILTER (WHERE status='OVERDUE') AS overdue_loans FROM customers c LEFT JOIN loans l ON false").fetch().one(); }

    public record CustomerRequest(String fullName, String dni, String phone, String email, String address, String notes) {}
    public record LoanRequest(UUID customerId, BigDecimal principal, BigDecimal interestRate, String ratePeriod, Integer termCount, String paymentFrequency, LocalDate disbursementDate, LocalDate firstDueDate, String notes, String requestId) {}
    public record PaymentRequest(BigDecimal amount, LocalDate paidAt, String paymentMethod, String notes, UUID installmentId, String requestId) {}
    public record ReversePaymentRequest(String reason) {}
    public record ExtensionRequest(LocalDate newDueDate, BigDecimal charge, String notes) {}
    public record RenewalRequest(LocalDate newDueDate, BigDecimal charge, String notes) {}
    public record CashRequest(String movementType, BigDecimal amount, String source, UUID referenceId, String notes) {}
    public record NotificationRequest(String recipient, String message, UUID referenceId) {}
}

