package com.medibook.controller;

import com.medibook.dto.Dtos.*;
import com.medibook.entity.DoctorProfile;
import com.medibook.security.CurrentUser;
import com.medibook.service.AvailabilityService;
import com.medibook.service.DashboardService;
import com.medibook.service.DoctorService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Doctor self-service. The doctor is always identified from the token, never from an id in the URL. */
@RestController
@RequestMapping("/api/doctor")
public class DoctorController {
    private final DoctorService doctors;
    private final AvailabilityService availability;
    private final DashboardService dashboard;

    public DoctorController(DoctorService doctors, AvailabilityService availability, DashboardService dashboard) {
        this.doctors = doctors;
        this.availability = availability;
        this.dashboard = dashboard;
    }

    @GetMapping("/profile")
    public DoctorSelfView profile(@AuthenticationPrincipal CurrentUser me) { return doctors.self(me); }

    @PutMapping("/profile")
    public DoctorSelfView update(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody UpdateDoctorProfileRequest r) {
        return doctors.updateSelf(me, r);
    }

    @GetMapping("/availability")
    public List<AvailabilityDto> availability(@AuthenticationPrincipal CurrentUser me) {
        DoctorProfile d = doctors.requireOwn(me);
        return availability.schedule(d.getId());
    }

    @PutMapping("/availability")
    public AvailabilityUpdateResult updateAvailability(@AuthenticationPrincipal CurrentUser me,
                                                       @RequestBody List<AvailabilityDto> schedule) {
        return availability.update(me, schedule);
    }

    @GetMapping("/dashboard")
    public DoctorDashboard dashboard(@AuthenticationPrincipal CurrentUser me, @RequestParam(required = false) LocalDate from,
                                     @RequestParam(required = false) LocalDate to) {
        return dashboard.doctor(me, from, to);
    }

    @GetMapping("/reviews")
    public PageResponse<ReviewDto> reviews(@AuthenticationPrincipal CurrentUser me, @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return doctors.ownReviews(me, page, size);
    }
}
