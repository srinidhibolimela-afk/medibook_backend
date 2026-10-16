package com.medibook.service;

import com.medibook.dto.Dtos.*;
import com.medibook.entity.*;
import com.medibook.repository.*;
import com.medibook.security.CurrentUser;
import com.medibook.util.Mapper;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;

/** All dashboard numbers are computed from the database on every call. Nothing is hard-coded. */
@Service
@Transactional(readOnly = true)
public class DashboardService {
    private final AppointmentRepository appointments;
    private final UserRepository users;
    private final DoctorProfileRepository doctors;
    private final ReviewRepository reviews;
    private final AuditLogRepository auditLogs;
    private final AppointmentService appointmentService;
    private final Mapper mapper;
    private final Clock clock;

    public DashboardService(AppointmentRepository appointments, UserRepository users, DoctorProfileRepository doctors,
                            ReviewRepository reviews, AuditLogRepository auditLogs, AppointmentService appointmentService,
                            Mapper mapper, Clock clock) {
        this.appointments = appointments;
        this.users = users;
        this.doctors = doctors;
        this.reviews = reviews;
        this.auditLogs = auditLogs;
        this.appointmentService = appointmentService;
        this.mapper = mapper;
        this.clock = clock;
    }

    public DoctorDashboard doctor(CurrentUser me, LocalDate from, LocalDate to) {
        DoctorProfile d = doctors.findByUserId(me.id()).orElseThrow(() -> com.medibook.exception.ApiException.notFound("Doctor profile not found"));
        LocalDate[] range = range(from, to);
        List<Appointment> inRange = appointments.findByDoctorIdAndAppointmentDateBetween(d.getId(), range[0], range[1]);
        LocalDate today = LocalDate.now(clock);
        long todayCount = inRange.stream().filter(a -> a.getAppointmentDate().equals(today)
                && AppointmentStatus.SLOT_HOLDING.contains(a.getStatus())).count();

        List<Appointment> upcoming = appointments.findByDoctorIdAndStatusInAndAppointmentDateGreaterThanEqual(
                d.getId(), AppointmentStatus.OPEN, today);
        upcoming.sort(Comparator.comparing(Appointment::getAppointmentDate).thenComparing(Appointment::getStartTime));

        Double avg = null;
        long count = 0;
        for (Object[] row : reviews.ratingStats()) {
            if (d.getId().equals(row[0])) { avg = Math.round(((Number) row[1]).doubleValue() * 10.0) / 10.0; count = ((Number) row[2]).longValue(); }
        }
        return new DoctorDashboard(byStatus(inRange), todayCount, daily(inRange, range[0], range[1]), avg, count,
                upcoming.stream().limit(6).map(a -> appointmentService.toDto(a, me)).toList());
    }

    public AdminDashboard admin(CurrentUser me, LocalDate from, LocalDate to) {
        LocalDate[] range = range(from, to);
        List<Appointment> inRange = appointments.findByAppointmentDateBetween(range[0], range[1]);

        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("patients", users.countByRole(Role.PATIENT));
        totals.put("doctors", users.countByRole(Role.DOCTOR));
        totals.put("verifiedDoctors", doctors.countByVerificationStatus(VerificationStatus.VERIFIED));
        totals.put("pendingDoctors", doctors.countByVerificationStatus(VerificationStatus.PENDING));
        totals.put("appointments", appointments.count());
        totals.put("appointmentsInRange", (long) inRange.size());

        LocalDate today = LocalDate.now(clock);
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Appointment> last8Weeks = appointments.findByAppointmentDateBetween(weekStart.minusWeeks(7), weekStart.plusDays(6));
        YearMonth thisMonth = YearMonth.from(today);
        List<Appointment> last6Months = appointments.findByAppointmentDateBetween(thisMonth.minusMonths(5).atDay(1), thisMonth.atEndOfMonth());

        List<SeriesPoint> weekly = new ArrayList<>();
        for (int i = 7; i >= 0; i--) {
            LocalDate ws = weekStart.minusWeeks(i);
            LocalDate we = ws.plusDays(6);
            weekly.add(point(ws.format(DateTimeFormatter.ofPattern("d MMM")), last8Weeks.stream()
                    .filter(a -> !a.getAppointmentDate().isBefore(ws) && !a.getAppointmentDate().isAfter(we)).toList()));
        }
        List<SeriesPoint> monthly = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth ym = thisMonth.minusMonths(i);
            monthly.add(point(ym.format(DateTimeFormatter.ofPattern("MMM yyyy")), last6Months.stream()
                    .filter(a -> YearMonth.from(a.getAppointmentDate()).equals(ym)).toList()));
        }
        Map<String, Long> bySpec = new TreeMap<>();
        for (Appointment a : inRange) bySpec.merge(a.getDoctor().getSpecialization().getName(), 1L, Long::sum);
        List<NameCount> specs = bySpec.entrySet().stream().map(e -> new NameCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(NameCount::count).reversed()).toList();

        return new AdminDashboard(totals, byStatus(inRange), revenue(inRange), daily(inRange, range[0], range[1]), weekly, monthly,
                specs, auditLogs.findTop8ByOrderByCreatedAtDesc().stream().map(mapper::audit).toList());
    }

    // ---------------------------------------------------------------- helpers (also used by reports)
    public static BigDecimal revenue(List<Appointment> list) {
        return list.stream().filter(a -> a.getStatus() == AppointmentStatus.COMPLETED)
                .map(Appointment::getFeeAtBooking).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private SeriesPoint point(String label, List<Appointment> list) {
        return new SeriesPoint(label, list.size(), revenue(list));
    }

    private Map<String, Long> byStatus(List<Appointment> list) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (AppointmentStatus s : AppointmentStatus.values()) m.put(s.name(), 0L);
        list.forEach(a -> m.merge(a.getStatus().name(), 1L, Long::sum));
        return m;
    }

    private List<SeriesPoint> daily(List<Appointment> list, LocalDate from, LocalDate to) {
        Map<LocalDate, List<Appointment>> g = new HashMap<>();
        list.forEach(a -> g.computeIfAbsent(a.getAppointmentDate(), k -> new ArrayList<>()).add(a));
        List<SeriesPoint> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(point(d.toString(), g.getOrDefault(d, List.of())));
        }
        return out;
    }

    /** Default range = last 30 days. Capped at 366 days so charts stay readable and queries cheap. */
    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate t = to != null ? to : LocalDate.now(clock).plusDays(14);
        LocalDate f = from != null ? from : LocalDate.now(clock).minusDays(30);
        if (f.isAfter(t)) { LocalDate x = f; f = t; t = x; }
        if (f.plusDays(366).isBefore(t)) f = t.minusDays(366);
        return new LocalDate[]{f, t};
    }
}
