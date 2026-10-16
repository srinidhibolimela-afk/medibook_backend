package com.medibook.util;

import com.medibook.dto.Dtos.*;
import com.medibook.entity.*;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Entity -> DTO conversions shared by several services. Call inside a transaction (lazy relations). */
@Component
public class Mapper {

    public UserView userView(UserAccount u, DoctorProfile dp) {
        return new UserView(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getRole(), u.isDemo(),
                dp == null ? null : dp.getId(), dp == null ? null : dp.getVerificationStatus());
    }

    public DoctorCard card(DoctorProfile d, Double avg, long count, LocalDateTime next) {
        return new DoctorCard(d.getId(), d.getUser().getFullName(), d.getSpecialization().getId(),
                d.getSpecialization().getName(), d.getQualifications(), d.getExperienceYears(), d.getConsultationFee(),
                d.getClinicName(), d.getLocation(), d.getPhotoUrl(), d.getVerificationStatus(),
                avg == null ? null : Math.round(avg * 10.0) / 10.0, count, next, d.getUser().isDemo());
    }

    public DoctorSelfView selfView(DoctorProfile d) {
        UserAccount u = d.getUser();
        return new DoctorSelfView(d.getId(), u.getFullName(), u.getEmail(), u.getPhone(), d.getSpecialization().getId(),
                d.getSpecialization().getName(), d.getQualifications(), d.getExperienceYears(), d.getConsultationFee(),
                d.getClinicName(), d.getLocation(), d.getBio(), d.getPhotoUrl(), d.getVerificationStatus(),
                d.getVerificationNote());
    }

    public AvailabilityDto availability(DoctorAvailability a) {
        return new AvailabilityDto(a.getDayOfWeek(), a.getStartTime(), a.getEndTime(), a.getSlotMinutes(), a.isActive());
    }

    /** "Priya Sharma" -> "Priya S." so public review lists do not expose full patient names. */
    public static String shortName(String fullName) {
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) return parts[0];
        return parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }

    public ReviewDto review(Review r, boolean adminView) {
        return new ReviewDto(r.getId(), r.getRating(), r.getComment(),
                adminView ? r.getPatient().getFullName() : shortName(r.getPatient().getFullName()),
                r.getDoctor().getId(), r.getDoctor().getUser().getFullName(), r.getAppointment().getReference(),
                r.isHidden(), adminView ? r.getHiddenReason() : null, r.getCreatedAt());
    }

    public NotificationDto notification(Notification n) {
        return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getAppointmentId(),
                n.isReadFlag(), n.getCreatedAt());
    }

    public AuditLogDto audit(AuditLog a) {
        return new AuditLogDto(a.getId(), a.getActorEmail(), a.getActorRole(), a.getAction(), a.getTargetType(),
                a.getTargetId(), a.getDetails(), a.getCreatedAt());
    }
}
