package com.medibook.service;

import com.medibook.config.AppProperties;
import com.medibook.dto.Dtos.*;
import com.medibook.entity.*;
import com.medibook.exception.ApiException;
import com.medibook.repository.*;
import com.medibook.security.CurrentUser;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The heart of the system: the full appointment lifecycle.
 * Every rule (ownership, status transitions, cancellation/reschedule windows, slot validity) is enforced here,
 * inside a transaction, regardless of what the frontend does.
 */
@Service
@Transactional
public class AppointmentService {
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String REF_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final AppointmentRepository appointments;
    private final DoctorProfileRepository doctors;
    private final UserRepository users;
    private final PatientProfileRepository patientProfiles;
    private final ReviewRepository reviews;
    private final AvailabilityService availability;
    private final NotificationService notifications;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;
    private final Random random = new java.security.SecureRandom();

    public AppointmentService(AppointmentRepository appointments, DoctorProfileRepository doctors, UserRepository users,
                              PatientProfileRepository patientProfiles, ReviewRepository reviews,
                              AvailabilityService availability, NotificationService notifications, AuditService audit,
                              AppProperties props, Clock clock) {
        this.appointments = appointments;
        this.doctors = doctors;
        this.users = users;
        this.patientProfiles = patientProfiles;
        this.reviews = reviews;
        this.availability = availability;
        this.notifications = notifications;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    // =============================================================== booking
    public AppointmentDto book(CurrentUser me, BookRequest r) {
        UserAccount patient = users.findById(me.id()).orElseThrow(() -> ApiException.unauthorized("Account not found"));
        DoctorProfile doctor = doctors.findById(r.doctorId()).orElseThrow(() -> ApiException.notFound("Doctor not found"));
        if (doctor.getVerificationStatus() != VerificationStatus.VERIFIED || !doctor.getUser().isActive()) {
            throw ApiException.badRequest("This doctor is not currently accepting appointments.");
        }
        LocalTime end = availability.requireBookable(doctor, r.date(), r.time());

        if (appointments.existsByPatientIdAndAppointmentDateAndStartTimeAndStatusIn(
                patient.getId(), r.date(), r.time(), AppointmentStatus.SLOT_HOLDING)) {
            throw ApiException.conflict("You already have an appointment at that date and time.");
        }

        Appointment a = new Appointment();
        a.setReference(newReference());
        a.setPatient(patient);
        a.setDoctor(doctor);
        a.setAppointmentDate(r.date());
        a.setStartTime(r.time());
        a.setEndTime(end);
        a.setReason(r.reason().trim());
        a.setFeeAtBooking(doctor.getConsultationFee());
        a.setStatus(AppointmentStatus.PENDING);
        a.syncSlotKey();
        // saveAndFlush so the unique index on slotKey fires NOW: if two patients race, the loser gets a 409.
        appointments.saveAndFlush(a);

        notifications.notify(doctor.getUser(), "APPOINTMENT_REQUESTED", "New appointment request",
                patient.getFullName() + " requested " + describe(a) + ". Please confirm or reject.", a.getId());
        return toDto(a, me);
    }

    // =============================================================== doctor decisions
    public AppointmentDto confirm(CurrentUser me, Long id) {
        Appointment a = ownedByDoctor(me, id);
        move(a, AppointmentStatus.CONFIRMED);
        notifications.notify(a.getPatient(), "APPOINTMENT_CONFIRMED", "Appointment confirmed",
                a.getDoctor().getUser().getFullName() + " confirmed your appointment " + a.getReference() + " for " + describe(a) + ".", a.getId());
        return toDto(a, me);
    }

    public AppointmentDto reject(CurrentUser me, Long id, String reason) {
        Appointment a = ownedByDoctor(me, id);
        move(a, AppointmentStatus.REJECTED);
        a.setCancelReason(clean(reason));
        notifications.notify(a.getPatient(), "APPOINTMENT_REJECTED", "Appointment declined",
                a.getDoctor().getUser().getFullName() + " could not accept your request for " + describe(a)
                        + (a.getCancelReason() == null ? "." : ". Reason: " + a.getCancelReason()), a.getId());
        return toDto(a, me);
    }

    public AppointmentDto complete(CurrentUser me, Long id, String visitNotes) {
        Appointment a = ownedByDoctor(me, id);
        LocalDateTime start = LocalDateTime.of(a.getAppointmentDate(), a.getStartTime());
        LocalDateTime now = LocalDateTime.now(clock);
        boolean tooEarly = props.rules().allowEarlyCompletion()
                ? a.getAppointmentDate().isAfter(now.toLocalDate())
                : start.isAfter(now);
        if (tooEarly) throw ApiException.badRequest("A visit can only be completed once its appointment time has arrived.");
        move(a, AppointmentStatus.COMPLETED);
        if (visitNotes != null && !visitNotes.isBlank()) a.setVisitNotes(visitNotes.trim());
        notifications.notify(a.getPatient(), "APPOINTMENT_COMPLETED", "Visit completed",
                "Your visit with " + a.getDoctor().getUser().getFullName() + " is marked complete. You can now leave a review.", a.getId());
        return toDto(a, me);
    }

    public AppointmentDto saveNotes(CurrentUser me, Long id, String visitNotes) {
        Appointment a = ownedByDoctor(me, id);
        if (a.getStatus() != AppointmentStatus.CONFIRMED && a.getStatus() != AppointmentStatus.COMPLETED) {
            throw ApiException.badRequest("Notes can only be added to confirmed or completed visits.");
        }
        a.setVisitNotes(visitNotes == null || visitNotes.isBlank() ? null : visitNotes.trim());
        a.setUpdatedAt(Instant.now());
        return toDto(a, me);
    }

    // =============================================================== cancel / reschedule
    public AppointmentDto cancel(CurrentUser me, Long id, String reason) {
        Appointment a = participant(me, id);
        boolean isPatient = isPatientOf(a, me);
        boolean isDoctor = isDoctorOf(a, me);
        if (!isPatient && !isDoctor) throw ApiException.forbidden("Only the patient or the doctor can cancel this appointment.");
        if (!AppointmentStatus.OPEN.contains(a.getStatus())) {
            throw ApiException.conflict("This appointment is " + a.getStatus().name().toLowerCase() + " and cannot be cancelled.");
        }
        if (!canCancel(a, me)) {
            throw ApiException.badRequest("Confirmed appointments can only be cancelled at least "
                    + props.rules().cancellationMinHours() + " hours before the start time.");
        }
        move(a, AppointmentStatus.CANCELLED);
        a.setCancelReason(clean(reason));
        UserAccount other = isPatient ? a.getDoctor().getUser() : a.getPatient();
        String who = isPatient ? a.getPatient().getFullName() : a.getDoctor().getUser().getFullName();
        notifications.notify(other, "APPOINTMENT_CANCELLED", "Appointment cancelled",
                who + " cancelled appointment " + a.getReference() + " (" + describe(a) + ")"
                        + (a.getCancelReason() == null ? "." : ". Reason: " + a.getCancelReason()), a.getId());
        return toDto(a, me);
    }

    public AppointmentDto reschedule(CurrentUser me, Long id, RescheduleRequest r) {
        Appointment a = participant(me, id);
        boolean isPatient = isPatientOf(a, me);
        boolean isDoctor = isDoctorOf(a, me);
        if (!isPatient && !isDoctor) throw ApiException.forbidden("Only the patient or the doctor can reschedule this appointment.");
        if (!AppointmentStatus.OPEN.contains(a.getStatus())) {
            throw ApiException.conflict("Only pending or confirmed appointments can be rescheduled.");
        }
        if (a.getRescheduleCount() >= props.rules().maxReschedules()) {
            throw ApiException.badRequest("This appointment has already been rescheduled the maximum "
                    + props.rules().maxReschedules() + " times.");
        }
        if (hoursUntilStart(a) < props.rules().rescheduleMinHours()) {
            throw ApiException.badRequest("Appointments can only be rescheduled at least "
                    + props.rules().rescheduleMinHours() + " hours before the start time.");
        }
        if (r.date().equals(a.getAppointmentDate()) && r.time().equals(a.getStartTime())) {
            throw ApiException.badRequest("Choose a different date or time.");
        }
        LocalTime end = availability.requireBookable(a.getDoctor(), r.date(), r.time());
        String old = describe(a);

        a.setAppointmentDate(r.date());
        a.setStartTime(r.time());
        a.setEndTime(end);
        a.setRescheduleCount(a.getRescheduleCount() + 1);
        a.setReminderSent(false);
        // A patient-initiated change must be re-approved by the doctor; a doctor-initiated move keeps its status.
        if (isPatient && a.getStatus() == AppointmentStatus.CONFIRMED) a.setStatus(AppointmentStatus.PENDING);
        a.syncSlotKey();
        appointments.saveAndFlush(a);

        UserAccount other = isPatient ? a.getDoctor().getUser() : a.getPatient();
        String who = isPatient ? a.getPatient().getFullName() : a.getDoctor().getUser().getFullName();
        notifications.notify(other, "APPOINTMENT_RESCHEDULED", "Appointment rescheduled",
                who + " moved appointment " + a.getReference() + " from " + old + " to " + describe(a)
                        + (isPatient ? ". Please confirm the new time." : "."), a.getId());
        return toDto(a, me);
    }

    // =============================================================== queries
    @Transactional(readOnly = true)
    public AppointmentDto get(CurrentUser me, Long id) { return toDto(participant(me, id), me); }

    @Transactional(readOnly = true)
    public ReceiptDto receipt(CurrentUser me, Long id) {
        Appointment a = participant(me, id);
        if (!isPatientOf(a, me) && !isDoctorOf(a, me) && me.role() != Role.ADMIN) throw ApiException.forbidden("Not allowed");
        if (a.getStatus() == AppointmentStatus.REJECTED || a.getStatus() == AppointmentStatus.CANCELLED) {
            throw ApiException.badRequest("No confirmation is available for a " + a.getStatus().name().toLowerCase() + " appointment.");
        }
        return new ReceiptDto(toDto(a, me), a.getPatient().getEmail(), a.getDoctor().getQualifications(), Instant.now(),
                "Consultation fee is payable at the clinic. MediBook does not process online payments.");
    }

    public record Filter(String q, AppointmentStatus status, LocalDate from, LocalDate to, LocalDate date, Long doctorId, String sort) {}

    @Transactional(readOnly = true)
    public PageResponse<AppointmentDto> list(CurrentUser me, Filter f, int page, int size) {
        Specification<Appointment> spec = buildSpec(me, f);
        Sort sort = "date_asc".equals(f.sort())
                ? Sort.by("appointmentDate", "startTime") : Sort.by(Sort.Direction.DESC, "appointmentDate", "startTime");
        var result = appointments.findAll(spec, PageRequest.of(Math.max(page, 0), AuditService.clamp(size), sort));
        return PageResponse.of(result.map(a -> toDto(a, me)));
    }

    @Transactional(readOnly = true)
    public List<AppointmentDto> calendar(CurrentUser me, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 62) {
            throw ApiException.badRequest("Calendar range must be between 1 and 62 days.");
        }
        Filter f = new Filter(null, null, from, to, null, null, "date_asc");
        return appointments.findAll(buildSpec(me, f), Sort.by("appointmentDate", "startTime")).stream()
                .map(a -> toDto(a, me)).toList();
    }

    /** Future pending/confirmed appointments that no longer match the doctor's schedule (needs admin attention). */
    @Transactional(readOnly = true)
    public List<AppointmentDto> conflicts(CurrentUser admin) {
        List<Appointment> open = appointments.findByStatusInAndAppointmentDateGreaterThanEqual(AppointmentStatus.OPEN, LocalDate.now(clock));
        return availability.conflicts(open).stream().map(a -> toDto(a, admin)).toList();
    }

    /** Admin conflict resolution: cancel an appointment and tell both parties. */
    public AppointmentDto adminCancel(CurrentUser admin, Long id, String reason) {
        Appointment a = appointments.findById(id).orElseThrow(() -> ApiException.notFound("Appointment not found"));
        if (!AppointmentStatus.OPEN.contains(a.getStatus())) {
            throw ApiException.conflict("Only pending or confirmed appointments can be cancelled.");
        }
        move(a, AppointmentStatus.CANCELLED);
        a.setCancelReason(clean(reason) == null ? "Cancelled by administrator" : "Cancelled by administrator: " + clean(reason));
        String msg = "Appointment " + a.getReference() + " (" + describe(a) + ") was cancelled by an administrator. " + a.getCancelReason() + ".";
        notifications.notify(a.getPatient(), "APPOINTMENT_CANCELLED", "Appointment cancelled", msg, a.getId());
        notifications.notify(a.getDoctor().getUser(), "APPOINTMENT_CANCELLED", "Appointment cancelled", msg, a.getId());
        audit.log(admin, "APPOINTMENT_CANCELLED_BY_ADMIN", "Appointment", a.getId(), a.getReference() + ": " + a.getCancelReason());
        return toDto(a, admin);
    }

    // =============================================================== scheduled reminders
    /** Called by ReminderScheduler. Reminds both sides about confirmed visits starting within the next 24 hours. */
    public int sendDueReminders() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime horizon = now.plusHours(24);
        int sent = 0;
        for (Appointment a : appointments.findByStatusAndReminderSentFalseAndAppointmentDateBetween(
                AppointmentStatus.CONFIRMED, now.toLocalDate(), horizon.toLocalDate())) {
            LocalDateTime start = LocalDateTime.of(a.getAppointmentDate(), a.getStartTime());
            if (start.isBefore(now) || start.isAfter(horizon)) continue;
            notifications.notify(a.getPatient(), "APPOINTMENT_REMINDER", "Appointment reminder",
                    "Reminder: you have an appointment with " + a.getDoctor().getUser().getFullName() + " on " + describe(a) + ".", a.getId());
            notifications.notify(a.getDoctor().getUser(), "APPOINTMENT_REMINDER", "Appointment reminder",
                    "Reminder: " + a.getPatient().getFullName() + " is scheduled on " + describe(a) + ".", a.getId());
            a.setReminderSent(true);
            sent++;
        }
        return sent;
    }

    // =============================================================== mapping & helpers
    /** Maps to a DTO shaped for the viewer. Private data is only filled in for the treating doctor. */
    public AppointmentDto toDto(Appointment a, CurrentUser viewer) {
        DoctorProfile d = a.getDoctor();
        UserAccount p = a.getPatient();
        boolean treatingDoctor = viewer.role() == Role.DOCTOR && isDoctorOf(a, viewer);
        PatientInfo info = null;
        if (treatingDoctor && AppointmentStatus.SLOT_HOLDING.contains(a.getStatus())) {
            PatientProfile pp = patientProfiles.findByUserId(p.getId()).orElse(null);
            Integer age = pp != null && pp.getDateOfBirth() != null
                    ? Period.between(pp.getDateOfBirth(), LocalDate.now(clock)).getYears() : null;
            info = new PatientInfo(p.getFullName(), age, pp == null ? null : pp.getGender(),
                    pp == null ? null : pp.getBloodGroup(), pp == null ? null : pp.getAllergies(),
                    pp == null ? null : pp.getMedicalNotes(), pp == null ? null : pp.getEmergencyContact());
        }
        boolean reviewed = a.getStatus() == AppointmentStatus.COMPLETED && viewer.role() == Role.PATIENT
                && reviews.existsByAppointmentId(a.getId());
        return new AppointmentDto(a.getId(), a.getReference(), a.getStatus().name(), a.getAppointmentDate(), a.getStartTime(),
                a.getEndTime(), a.getReason(), a.getCancelReason(), a.getFeeAtBooking(), a.getRescheduleCount(),
                canCancel(a, viewer), canReschedule(a, viewer), reviewed,
                d.getId(), d.getUser().getFullName(), d.getSpecialization().getName(), d.getClinicName(), d.getLocation(),
                d.getPhotoUrl(), p.getId(), p.getFullName(), p.getPhone(), info,
                treatingDoctor ? a.getVisitNotes() : null, a.getCreatedAt());
    }

    private boolean canCancel(Appointment a, CurrentUser viewer) {
        if (!AppointmentStatus.OPEN.contains(a.getStatus()) || hoursUntilStart(a) <= 0) return false;
        if (isDoctorOf(a, viewer)) return true;
        if (!isPatientOf(a, viewer)) return false;
        return a.getStatus() == AppointmentStatus.PENDING || hoursUntilStart(a) >= props.rules().cancellationMinHours();
    }

    private boolean canReschedule(Appointment a, CurrentUser viewer) {
        if (!(isPatientOf(a, viewer) || isDoctorOf(a, viewer))) return false;
        return AppointmentStatus.OPEN.contains(a.getStatus()) && a.getRescheduleCount() < props.rules().maxReschedules()
                && hoursUntilStart(a) >= props.rules().rescheduleMinHours();
    }

    private double hoursUntilStart(Appointment a) {
        LocalDateTime start = LocalDateTime.of(a.getAppointmentDate(), a.getStartTime());
        return Duration.between(LocalDateTime.now(clock), start).toMinutes() / 60.0;
    }

    private void move(Appointment a, AppointmentStatus next) {
        if (!a.getStatus().canMoveTo(next)) {
            throw ApiException.conflict("An appointment that is " + a.getStatus().name().toLowerCase()
                    + " cannot become " + next.name().toLowerCase() + ".");
        }
        a.setStatus(next);
        a.syncSlotKey(); // frees the slot for cancelled/rejected
        appointments.saveAndFlush(a);
    }

    private Appointment load(Long id) {
        return appointments.findById(id).orElseThrow(() -> ApiException.notFound("Appointment not found"));
    }

    private Appointment ownedByDoctor(CurrentUser me, Long id) {
        Appointment a = load(id);
        if (me.role() != Role.DOCTOR || !isDoctorOf(a, me)) throw ApiException.forbidden("This appointment does not belong to you.");
        return a;
    }

    /** Patient, treating doctor or admin only. Everyone else gets 403. */
    private Appointment participant(CurrentUser me, Long id) {
        Appointment a = load(id);
        if (me.role() == Role.ADMIN || isPatientOf(a, me) || isDoctorOf(a, me)) return a;
        throw ApiException.forbidden("You do not have access to this appointment.");
    }

    private boolean isPatientOf(Appointment a, CurrentUser u) { return a.getPatient().getId().equals(u.id()); }
    private boolean isDoctorOf(Appointment a, CurrentUser u) { return a.getDoctor().getUser().getId().equals(u.id()); }

    private String describe(Appointment a) {
        return a.getAppointmentDate() + " at " + a.getStartTime().format(HHMM);
    }

    private static String clean(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private String newReference() {
        StringBuilder sb = new StringBuilder("MB-").append(LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)).append('-');
        for (int i = 0; i < 5; i++) sb.append(REF_CHARS.charAt(random.nextInt(REF_CHARS.length())));
        return sb.toString();
    }

    private Specification<Appointment> buildSpec(CurrentUser me, Filter f) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            Join<Appointment, UserAccount> patient = root.join("patient");
            Join<Appointment, DoctorProfile> doctor = root.join("doctor");
            Join<DoctorProfile, UserAccount> doctorUser = doctor.join("user");
            // Scope: users only ever see their own appointments; admins see all.
            if (me.role() == Role.PATIENT) p.add(cb.equal(patient.get("id"), me.id()));
            else if (me.role() == Role.DOCTOR) p.add(cb.equal(doctorUser.get("id"), me.id()));
            if (f.status() != null) p.add(cb.equal(root.get("status"), f.status()));
            if (f.from() != null) p.add(cb.greaterThanOrEqualTo(root.get("appointmentDate"), f.from()));
            if (f.to() != null) p.add(cb.lessThanOrEqualTo(root.get("appointmentDate"), f.to()));
            if (f.date() != null) p.add(cb.equal(root.get("appointmentDate"), f.date()));
            if (f.doctorId() != null && me.role() == Role.ADMIN) p.add(cb.equal(doctor.get("id"), f.doctorId()));
            if (f.q() != null && !f.q().isBlank()) {
                String like = "%" + f.q().trim().toLowerCase() + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("reference")), like),
                        cb.like(cb.lower(patient.get("fullName")), like),
                        cb.like(cb.lower(doctorUser.get("fullName")), like)));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }
}
