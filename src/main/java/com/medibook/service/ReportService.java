package com.medibook.service;

import com.medibook.dto.Dtos.RevenueRow;
import com.medibook.entity.Appointment;
import com.medibook.entity.AppointmentStatus;
import com.medibook.entity.DoctorProfile;
import com.medibook.exception.ApiException;
import com.medibook.repository.AppointmentRepository;
import com.medibook.repository.DoctorProfileRepository;
import com.medibook.security.CurrentUser;
import com.medibook.util.Csv;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;

/** Revenue = sum of the fee stored on COMPLETED appointments (fees are payable at the clinic; no payment gateway). */
@Service
@Transactional(readOnly = true)
public class ReportService {
    private final AppointmentRepository appointments;
    private final DoctorProfileRepository doctors;
    private final AuditService audit;
    private final Clock clock;

    public ReportService(AppointmentRepository appointments, DoctorProfileRepository doctors, AuditService audit, Clock clock) {
        this.appointments = appointments;
        this.doctors = doctors;
        this.audit = audit;
        this.clock = clock;
    }

    public List<RevenueRow> revenue(LocalDate from, LocalDate to) {
        LocalDate[] r = range(from, to);
        Map<Long, long[]> counts = new HashMap<>();
        Map<Long, BigDecimal> money = new HashMap<>();
        Map<Long, Appointment> sample = new HashMap<>();
        for (Appointment a : appointments.findByAppointmentDateBetween(r[0], r[1])) {
            if (a.getStatus() != AppointmentStatus.COMPLETED) continue;
            Long id = a.getDoctor().getId();
            counts.computeIfAbsent(id, k -> new long[1])[0]++;
            money.merge(id, a.getFeeAtBooking(), BigDecimal::add);
            sample.putIfAbsent(id, a);
        }
        List<RevenueRow> rows = new ArrayList<>();
        for (var e : sample.entrySet()) {
            DoctorProfile d = e.getValue().getDoctor();
            rows.add(new RevenueRow(d.getId(), d.getUser().getFullName(), d.getSpecialization().getName(),
                    counts.get(d.getId())[0], money.get(d.getId())));
        }
        rows.sort(Comparator.comparing(RevenueRow::revenue).reversed());
        return rows;
    }

    public byte[] revenueCsv(CurrentUser admin, LocalDate from, LocalDate to) {
        List<List<Object>> rows = new ArrayList<>();
        for (RevenueRow x : revenue(from, to)) {
            rows.add(List.of(x.doctorName(), x.specialization(), x.completedVisits(), x.revenue()));
        }
        audit.log(admin, "REPORT_EXPORTED", "Report", null, "revenue " + from + " to " + to);
        return Csv.build(List.of("Doctor", "Specialization", "Completed visits", "Revenue"), rows);
    }

    public byte[] appointmentsCsv(CurrentUser admin, LocalDate from, LocalDate to, AppointmentStatus status) {
        LocalDate[] r = range(from, to);
        List<List<Object>> rows = new ArrayList<>();
        for (Appointment a : appointments.findByAppointmentDateBetween(r[0], r[1])) {
            if (status != null && a.getStatus() != status) continue;
            rows.add(List.of(a.getReference(), a.getAppointmentDate(), a.getStartTime(), a.getStatus(),
                    a.getPatient().getFullName(), a.getDoctor().getUser().getFullName(),
                    a.getDoctor().getSpecialization().getName(), a.getFeeAtBooking()));
            // Reason and visit notes are intentionally NOT exported: they can contain sensitive health information.
        }
        audit.log(admin, "REPORT_EXPORTED", "Report", null, "appointments " + r[0] + " to " + r[1] + (status == null ? "" : " status " + status));
        return Csv.build(List.of("Reference", "Date", "Start time", "Status", "Patient", "Doctor", "Specialization", "Fee"), rows);
    }

    public byte[] doctorsCsv(CurrentUser admin) {
        List<List<Object>> rows = new ArrayList<>();
        for (DoctorProfile d : doctors.findAll()) {
            rows.add(List.of(d.getUser().getFullName(), d.getUser().getEmail(), d.getSpecialization().getName(),
                    d.getExperienceYears(), d.getConsultationFee(), d.getLocation(), d.getVerificationStatus(),
                    d.getUser().isActive() ? "Active" : "Inactive", appointments.countByDoctorId(d.getId())));
        }
        audit.log(admin, "REPORT_EXPORTED", "Report", null, "doctors");
        return Csv.build(List.of("Name", "Email", "Specialization", "Experience (yrs)", "Fee", "Location", "Verification",
                "Account", "Total appointments"), rows);
    }

    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate t = to != null ? to : LocalDate.now(clock);
        LocalDate f = from != null ? from : t.minusDays(30);
        if (f.isAfter(t)) throw ApiException.badRequest("'from' must not be after 'to'.");
        if (f.plusDays(730).isBefore(t)) throw ApiException.badRequest("Report range cannot exceed 2 years.");
        return new LocalDate[]{f, t};
    }
}
