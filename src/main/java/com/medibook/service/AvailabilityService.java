package com.medibook.service;

import com.medibook.config.AppProperties;
import com.medibook.dto.Dtos.*;
import com.medibook.entity.*;
import com.medibook.exception.ApiException;
import com.medibook.repository.AppointmentRepository;
import com.medibook.repository.DoctorAvailabilityRepository;
import com.medibook.repository.DoctorProfileRepository;
import com.medibook.security.CurrentUser;
import com.medibook.util.Mapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Turns the doctor's weekly working hours into concrete bookable slots, and validates a chosen slot.
 * All slot decisions are made here on the server; the frontend only displays them.
 */
@Service
@Transactional
public class AvailabilityService {
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DIRECTORY_LOOKAHEAD_DAYS = 30;

    private final DoctorAvailabilityRepository availability;
    private final AppointmentRepository appointments;
    private final DoctorProfileRepository doctors;
    private final NotificationService notifications;
    private final Mapper mapper;
    private final Clock clock;
    private final AppProperties props;

    public AvailabilityService(DoctorAvailabilityRepository availability, AppointmentRepository appointments,
                               DoctorProfileRepository doctors, NotificationService notifications, Mapper mapper,
                               Clock clock, AppProperties props) {
        this.availability = availability;
        this.appointments = appointments;
        this.doctors = doctors;
        this.notifications = notifications;
        this.mapper = mapper;
        this.clock = clock;
        this.props = props;
    }

    // ---------------------------------------------------------------- schedule (doctor-owned)
    @Transactional(readOnly = true)
    public List<AvailabilityDto> schedule(Long doctorId) {
        return availability.findByDoctorIdOrderByDayOfWeek(doctorId).stream().map(mapper::availability).toList();
    }

    /** Replace the doctor's weekly schedule. Doctors can only ever change their own (id comes from the token). */
    public AvailabilityUpdateResult update(CurrentUser me, List<AvailabilityDto> requested) {
        DoctorProfile doctor = doctors.findByUserId(me.id()).orElseThrow(() -> ApiException.notFound("Doctor profile not found"));
        if (requested == null) throw ApiException.badRequest("Schedule is required.");
        Set<DayOfWeek> seen = new HashSet<>();
        for (AvailabilityDto a : requested) {
            if (a == null || a.dayOfWeek() == null || a.startTime() == null || a.endTime() == null) {
                throw ApiException.badRequest("Each schedule row needs a weekday, start time and end time.");
            }
            if (a.slotMinutes() < 10 || a.slotMinutes() > 120) {
                throw ApiException.badRequest("Consultation length must be between 10 and 120 minutes.");
            }
            if (!seen.add(a.dayOfWeek())) throw ApiException.badRequest("Each weekday can appear only once.");
            if (!a.endTime().isAfter(a.startTime())) {
                throw ApiException.badRequest(pretty(a.dayOfWeek()) + ": end time must be after start time.");
            }
            long minutes = Duration.between(a.startTime(), a.endTime()).toMinutes();
            if (minutes < a.slotMinutes()) {
                throw ApiException.badRequest(pretty(a.dayOfWeek()) + ": working hours are shorter than one consultation slot.");
            }
        }
        Map<DayOfWeek, DoctorAvailability> existing = availability.findByDoctorIdOrderByDayOfWeek(doctor.getId()).stream()
                .collect(Collectors.toMap(DoctorAvailability::getDayOfWeek, x -> x));
        // Update in place (avoids unique-constraint clashes from delete+insert ordering).
        for (AvailabilityDto a : requested) {
            DoctorAvailability row = existing.remove(a.dayOfWeek());
            if (row == null) { row = new DoctorAvailability(); row.setDoctor(doctor); row.setDayOfWeek(a.dayOfWeek()); }
            row.setStartTime(a.startTime());
            row.setEndTime(a.endTime());
            row.setSlotMinutes(a.slotMinutes());
            row.setActive(a.active());
            availability.save(row);
        }
        existing.values().forEach(availability::delete);
        availability.flush();

        // Which already-booked future appointments no longer fit the new schedule?
        List<Appointment> open = appointments.findByDoctorIdAndStatusInAndAppointmentDateGreaterThanEqual(
                doctor.getId(), AppointmentStatus.OPEN, LocalDate.now(clock));
        List<Appointment> affected = conflicts(open);
        for (Appointment ap : affected) {
            notifications.notify(ap.getPatient(), "AVAILABILITY_CHANGED", "Doctor changed their schedule",
                    doctor.getUser().getFullName() + " changed working hours. Your appointment " + ap.getReference() + " on "
                            + ap.getAppointmentDate() + " at " + ap.getStartTime().format(HHMM)
                            + " may need rescheduling. You can reschedule or cancel it from My appointments.",
                    ap.getId());
        }
        if (!affected.isEmpty()) {
            notifications.notify(doctor.getUser(), "AVAILABILITY_CHANGED", "Schedule change affects bookings",
                    affected.size() + " booked appointment(s) fall outside your new schedule. Patients were notified.", null);
        }
        return new AvailabilityUpdateResult(schedule(doctor.getId()), affected.size());
    }

    /** Appointments (any doctor) whose date/time is no longer inside that doctor's current weekly schedule. */
    @Transactional(readOnly = true)
    public List<Appointment> conflicts(List<Appointment> toCheck) {
        Map<Long, Map<DayOfWeek, DoctorAvailability>> byDoctor = new HashMap<>();
        List<Appointment> result = new ArrayList<>();
        for (Appointment ap : toCheck) {
            Long docId = ap.getDoctor().getId();
            Map<DayOfWeek, DoctorAvailability> sched = byDoctor.computeIfAbsent(docId, id ->
                    availability.findByDoctorIdOrderByDayOfWeek(id).stream()
                            .collect(Collectors.toMap(DoctorAvailability::getDayOfWeek, x -> x)));
            DoctorAvailability a = sched.get(ap.getAppointmentDate().getDayOfWeek());
            boolean fits = a != null && a.isActive() && starts(a).contains(ap.getStartTime());
            if (!fits) result.add(ap);
        }
        return result;
    }

    // ---------------------------------------------------------------- slots (public)
    @Transactional(readOnly = true)
    public List<SlotDto> slots(Long doctorId, LocalDate date) {
        LocalDate today = LocalDate.now(clock);
        if (date.isBefore(today) || date.isAfter(today.plusDays(props.rules().bookingWindowDays()))) return List.of();
        DoctorAvailability a = availability.findByDoctorIdOrderByDayOfWeek(doctorId).stream()
                .filter(x -> x.isActive() && x.getDayOfWeek() == date.getDayOfWeek()).findFirst().orElse(null);
        if (a == null) return List.of();
        Set<LocalTime> booked = appointments.findByDoctorIdAndAppointmentDateAndStatusIn(doctorId, date, AppointmentStatus.SLOT_HOLDING)
                .stream().map(Appointment::getStartTime).collect(Collectors.toSet());
        return buildSlots(a, date, booked, LocalDateTime.now(clock));
    }

    /** Dates in the next `days` days that still have at least one free slot (drives the booking calendar). */
    @Transactional(readOnly = true)
    public List<LocalDate> availableDates(Long doctorId, int days) {
        int window = Math.min(Math.max(days, 1), props.rules().bookingWindowDays());
        LocalDate today = LocalDate.now(clock);
        Map<DayOfWeek, DoctorAvailability> sched = availability.findByDoctorIdOrderByDayOfWeek(doctorId).stream()
                .filter(DoctorAvailability::isActive).collect(Collectors.toMap(DoctorAvailability::getDayOfWeek, x -> x));
        Set<LocalDateTime> booked = bookedBetween(doctorId, today, today.plusDays(window));
        LocalDateTime now = LocalDateTime.now(clock);
        List<LocalDate> out = new ArrayList<>();
        for (int i = 0; i <= window; i++) {
            LocalDate d = today.plusDays(i);
            DoctorAvailability a = sched.get(d.getDayOfWeek());
            if (a != null && hasFreeSlot(a, d, booked, now)) out.add(d);
        }
        return out;
    }

    /** Earliest free slot in the next 30 days, or empty. Used on directory cards. */
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> nextAvailable(Long doctorId, List<DoctorAvailability> doctorSchedule) {
        if (doctorSchedule == null || doctorSchedule.isEmpty()) return Optional.empty();
        LocalDate today = LocalDate.now(clock);
        int window = Math.min(DIRECTORY_LOOKAHEAD_DAYS, props.rules().bookingWindowDays());
        Set<LocalDateTime> booked = bookedBetween(doctorId, today, today.plusDays(window));
        LocalDateTime now = LocalDateTime.now(clock);
        Map<DayOfWeek, DoctorAvailability> sched = doctorSchedule.stream().filter(DoctorAvailability::isActive)
                .collect(Collectors.toMap(DoctorAvailability::getDayOfWeek, x -> x));
        for (int i = 0; i <= window; i++) {
            LocalDate d = today.plusDays(i);
            DoctorAvailability a = sched.get(d.getDayOfWeek());
            if (a == null) continue;
            for (SlotDto s : buildSlots(a, d, bookedTimesOn(booked, d), now)) {
                if (s.available()) return Optional.of(LocalDateTime.of(d, LocalTime.parse(s.time())));
            }
        }
        return Optional.empty();
    }

    /**
     * Server-side gatekeeper used by booking AND rescheduling. Throws a 400/409 if the chosen date/time is not
     * a real, free, future slot. Returns the slot end time.
     */
    @Transactional(readOnly = true)
    public LocalTime requireBookable(DoctorProfile doctor, LocalDate date, LocalTime time) {
        LocalDate today = LocalDate.now(clock);
        if (date.isBefore(today)) throw ApiException.badRequest("Appointments cannot be booked in the past.");
        if (date.isAfter(today.plusDays(props.rules().bookingWindowDays()))) {
            throw ApiException.badRequest("You can book at most " + props.rules().bookingWindowDays() + " days ahead.");
        }
        DoctorAvailability a = availability.findByDoctorIdOrderByDayOfWeek(doctor.getId()).stream()
                .filter(x -> x.isActive() && x.getDayOfWeek() == date.getDayOfWeek()).findFirst()
                .orElseThrow(() -> ApiException.badRequest("The doctor does not work on " + pretty(date.getDayOfWeek()) + "."));
        if (!starts(a).contains(time)) throw ApiException.badRequest("That is not a valid time slot for this doctor.");
        if (LocalDateTime.of(date, time).isBefore(LocalDateTime.now(clock).plusMinutes(props.rules().minLeadMinutes()))) {
            throw ApiException.badRequest("That slot is too soon or already in the past. Please pick a later slot.");
        }
        if (appointments.existsBySlotKey(Appointment.buildSlotKey(doctor.getId(), date, time))) {
            throw ApiException.conflict("That slot has just been booked by someone else. Please choose another.");
        }
        return time.plusMinutes(a.getSlotMinutes());
    }

    // ---------------------------------------------------------------- internals
    private Set<LocalDateTime> bookedBetween(Long doctorId, LocalDate from, LocalDate to) {
        return appointments.findByDoctorIdAndAppointmentDateBetweenAndStatusIn(doctorId, from, to, AppointmentStatus.SLOT_HOLDING)
                .stream().map(x -> LocalDateTime.of(x.getAppointmentDate(), x.getStartTime())).collect(Collectors.toSet());
    }

    private Set<LocalTime> bookedTimesOn(Set<LocalDateTime> booked, LocalDate d) {
        return booked.stream().filter(b -> b.toLocalDate().equals(d)).map(LocalDateTime::toLocalTime).collect(Collectors.toSet());
    }

    private boolean hasFreeSlot(DoctorAvailability a, LocalDate d, Set<LocalDateTime> booked, LocalDateTime now) {
        return buildSlots(a, d, bookedTimesOn(booked, d), now).stream().anyMatch(SlotDto::available);
    }

    private List<SlotDto> buildSlots(DoctorAvailability a, LocalDate date, Set<LocalTime> booked, LocalDateTime now) {
        LocalDateTime earliest = now.plusMinutes(props.rules().minLeadMinutes());
        List<SlotDto> out = new ArrayList<>();
        for (LocalTime t : starts(a)) {
            boolean free = !booked.contains(t) && !LocalDateTime.of(date, t).isBefore(earliest);
            out.add(new SlotDto(t.format(HHMM), free));
        }
        return out;
    }

    /** All slot start times for one weekday row, e.g. 09:00, 09:30, ... while the slot still ends by endTime. */
    public static List<LocalTime> starts(DoctorAvailability a) {
        List<LocalTime> out = new ArrayList<>();
        LocalTime t = a.getStartTime();
        while (!t.plusMinutes(a.getSlotMinutes()).isAfter(a.getEndTime()) && !t.plusMinutes(a.getSlotMinutes()).isBefore(t)) {
            out.add(t);
            t = t.plusMinutes(a.getSlotMinutes());
        }
        return out;
    }

    private static String pretty(DayOfWeek d) {
        String n = d.name();
        return n.charAt(0) + n.substring(1).toLowerCase();
    }
}
