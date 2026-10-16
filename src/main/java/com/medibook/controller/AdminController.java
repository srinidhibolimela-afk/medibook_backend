package com.medibook.controller;

import com.medibook.dto.Dtos.*;
import com.medibook.entity.AppointmentStatus;
import com.medibook.entity.Role;
import com.medibook.entity.VerificationStatus;
import com.medibook.security.CurrentUser;
import com.medibook.service.*;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Every endpoint here requires ROLE_ADMIN (URL rule in SecurityConfig + @PreAuthorize as a second lock). */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final AdminService admin;
    private final DashboardService dashboard;
    private final AppointmentService appointments;
    private final ReviewService reviews;
    private final ReportService reports;
    private final AuditService audit;

    public AdminController(AdminService admin, DashboardService dashboard, AppointmentService appointments,
                           ReviewService reviews, ReportService reports, AuditService audit) {
        this.admin = admin;
        this.dashboard = dashboard;
        this.appointments = appointments;
        this.reviews = reviews;
        this.reports = reports;
        this.audit = audit;
    }

    @GetMapping("/dashboard")
    public AdminDashboard dashboard(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) LocalDate from,
                                    @RequestParam(required = false) LocalDate to) {
        return dashboard.admin(me, from, to);
    }

    // ---- users & doctors
    @GetMapping("/users")
    public PageResponse<AdminUserDto> users(@RequestParam(required = false) Role role,
                                            @RequestParam(required = false) Boolean active,
                                            @RequestParam(required = false) VerificationStatus verification,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) String sort,
                                            @RequestParam(required = false) String dir,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        return admin.users(role, active, verification, q, sort, dir, page, size);
    }

    @PatchMapping("/users/{id}/active")
    public AdminUserDto setActive(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id, @RequestBody ActiveRequest r) {
        return admin.setActive(me, id, r.active());
    }

    @PostMapping("/doctors")
    public ResponseEntity<AdminUserDto> createDoctor(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody RegisterDoctorRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.createDoctor(me, r));
    }

    @PatchMapping("/doctors/{doctorId}/verification")
    public AdminUserDto verify(@AuthenticationPrincipal CurrentUser me, @PathVariable Long doctorId,
                               @Valid @RequestBody VerificationRequest r) {
        return admin.verify(me, doctorId, r);
    }

    // ---- specializations
    @GetMapping("/specializations")
    public List<SpecializationDto> specializations() { return admin.specializations(); }

    @PostMapping("/specializations")
    public ResponseEntity<SpecializationDto> createSpec(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody SpecializationRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.createSpecialization(me, r));
    }

    @PutMapping("/specializations/{id}")
    public SpecializationDto updateSpec(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id,
                                        @Valid @RequestBody SpecializationRequest r) {
        return admin.updateSpecialization(me, id, r);
    }

    @DeleteMapping("/specializations/{id}")
    public ResponseEntity<Void> deleteSpec(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id) {
        admin.deleteSpecialization(me, id);
        return ResponseEntity.noContent().build();
    }

    // ---- appointments
    @GetMapping("/appointments")
    public PageResponse<AppointmentDto> appointments(@AuthenticationPrincipal CurrentUser me,
                                                     @RequestParam(required = false) String q,
                                                     @RequestParam(required = false) AppointmentStatus status,
                                                     @RequestParam(required = false) Long doctorId,
                                                     @RequestParam(required = false) LocalDate from,
                                                     @RequestParam(required = false) LocalDate to,
                                                     @RequestParam(required = false) LocalDate date,
                                                     @RequestParam(required = false) String sort,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "10") int size) {
        return appointments.list(me, new AppointmentService.Filter(q, status, from, to, date, doctorId, sort), page, size);
    }

    @GetMapping("/appointments/conflicts")
    public List<AppointmentDto> conflicts(@AuthenticationPrincipal CurrentUser me) { return appointments.conflicts(me); }

    @PatchMapping("/appointments/{id}/cancel")
    public AppointmentDto cancel(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id,
                                 @Valid @RequestBody(required = false) ReasonRequest r) {
        return appointments.adminCancel(me, id, r == null ? null : r.reason());
    }

    // ---- reviews
    @GetMapping("/reviews")
    public PageResponse<ReviewDto> reviews(@RequestParam(required = false) Boolean hidden,
                                           @RequestParam(required = false) Integer maxRating,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return reviews.adminList(hidden, maxRating, q, page, size);
    }

    @PatchMapping("/reviews/{id}/moderation")
    public ReviewDto moderate(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id, @Valid @RequestBody HideReviewRequest r) {
        return reviews.moderate(me, id, r);
    }

    // ---- audit log
    @GetMapping("/audit-logs")
    public PageResponse<AuditLogDto> audit(@RequestParam(required = false) String q, @RequestParam(required = false) String action,
                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "15") int size) {
        return audit.search(q, action, page, size);
    }

    // ---- reports
    @GetMapping("/reports/revenue")
    public List<RevenueRow> revenue(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return reports.revenue(from, to);
    }

    @GetMapping("/reports/revenue.csv")
    public ResponseEntity<byte[]> revenueCsv(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to) {
        return csv(reports.revenueCsv(me, from, to), "revenue-report.csv");
    }

    @GetMapping("/reports/appointments.csv")
    public ResponseEntity<byte[]> appointmentsCsv(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) LocalDate from,
                                                  @RequestParam(required = false) LocalDate to,
                                                  @RequestParam(required = false) AppointmentStatus status) {
        return csv(reports.appointmentsCsv(me, from, to, status), "appointments-report.csv");
    }

    @GetMapping("/reports/doctors.csv")
    public ResponseEntity<byte[]> doctorsCsv(@AuthenticationPrincipal CurrentUser me) {
        return csv(reports.doctorsCsv(me), "doctors-report.csv");
    }

    private ResponseEntity<byte[]> csv(byte[] body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(body);
    }
}
