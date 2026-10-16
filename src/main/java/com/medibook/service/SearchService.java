package com.medibook.service;

import com.medibook.dto.Dtos.*;
import com.medibook.entity.Role;
import com.medibook.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Role-aware global search box. Each role only ever gets results it is allowed to see. */
@Service
@Transactional(readOnly = true)
public class SearchService {
    private final DoctorService doctorService;
    private final AppointmentService appointmentService;
    private final AdminService adminService;

    public SearchService(DoctorService doctorService, AppointmentService appointmentService, AdminService adminService) {
        this.doctorService = doctorService;
        this.appointmentService = appointmentService;
        this.adminService = adminService;
    }

    public SearchResult search(CurrentUser me, String q) {
        if (q == null || q.trim().length() < 2) return new SearchResult(List.of(), List.of(), List.of());
        List<DoctorCard> doctors = me.role() == Role.DOCTOR ? List.of()
                : doctorService.search(new DoctorService.Search(q, null, null, null, null, null, null, null, "name"), 0, 5).content();
        List<AppointmentDto> appts = appointmentService.list(me,
                new AppointmentService.Filter(q, null, null, null, null, null, "date_desc"), 0, 5).content();
        List<AdminUserDto> found = me.role() == Role.ADMIN
                ? adminService.users(null, null, null, q, "name", "asc", 0, 5).content() : List.of();
        return new SearchResult(doctors, appts, found);
    }
}
