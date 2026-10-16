package com.medibook.controller;

import com.medibook.dto.Dtos.*;
import com.medibook.entity.AppointmentStatus;
import com.medibook.security.CurrentUser;
import com.medibook.service.AppointmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Appointment endpoints. Role checks are declared with @PreAuthorize; ownership checks (is this YOUR appointment?)
 * happen again inside AppointmentService.
 */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {
    private final AppointmentService service;

    public AppointmentController(AppointmentService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasRole('PATIENT')")
    public ResponseEntity<AppointmentDto> book(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody BookRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.book(me, r));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('PATIENT','DOCTOR')")
    public PageResponse<AppointmentDto> mine(@AuthenticationPrincipal CurrentUser me,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false) AppointmentStatus status,
                                             @RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to,
                                             @RequestParam(required = false) LocalDate date,
                                             @RequestParam(required = false) String sort,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "10") int size) {
        return service.list(me, new AppointmentService.Filter(q, status, from, to, date, null, sort), page, size);
    }

    @GetMapping("/calendar")
    @PreAuthorize("hasAnyRole('PATIENT','DOCTOR')")
    public List<AppointmentDto> calendar(@AuthenticationPrincipal CurrentUser me, @RequestParam LocalDate from,
                                         @RequestParam LocalDate to) {
        return service.calendar(me, from, to);
    }

    @GetMapping("/{id}")
    public AppointmentDto get(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id) { return service.get(me, id); }

    @GetMapping("/{id}/receipt")
    public ReceiptDto receipt(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id) { return service.receipt(me, id); }

    @PatchMapping("/{id}/confirm")
    @PreAuthorize("hasRole('DOCTOR')")
    public AppointmentDto confirm(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id) { return service.confirm(me, id); }

    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasRole('DOCTOR')")
    public AppointmentDto reject(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id,
                                 @Valid @RequestBody(required = false) ReasonRequest r) {
        return service.reject(me, id, r == null ? null : r.reason());
    }

    @PatchMapping("/{id}/complete")
    @PreAuthorize("hasRole('DOCTOR')")
    public AppointmentDto complete(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id,
                                   @Valid @RequestBody(required = false) CompleteRequest r) {
        return service.complete(me, id, r == null ? null : r.visitNotes());
    }

    @PutMapping("/{id}/notes")
    @PreAuthorize("hasRole('DOCTOR')")
    public AppointmentDto notes(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id, @Valid @RequestBody NotesRequest r) {
        return service.saveNotes(me, id, r.visitNotes());
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('PATIENT','DOCTOR')")
    public AppointmentDto cancel(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id,
                                 @Valid @RequestBody(required = false) ReasonRequest r) {
        return service.cancel(me, id, r == null ? null : r.reason());
    }

    @PatchMapping("/{id}/reschedule")
    @PreAuthorize("hasAnyRole('PATIENT','DOCTOR')")
    public AppointmentDto reschedule(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id,
                                     @Valid @RequestBody RescheduleRequest r) {
        return service.reschedule(me, id, r);
    }
}
