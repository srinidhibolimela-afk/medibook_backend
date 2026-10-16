package com.medibook.service;

import com.medibook.dto.Dtos.*;
import com.medibook.entity.*;
import com.medibook.exception.ApiException;
import com.medibook.repository.*;
import com.medibook.security.CurrentUser;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** User management, doctor verification and specializations. Every mutation writes an audit-log entry. */
@Service
@Transactional
public class AdminService {
    private final UserRepository users;
    private final DoctorProfileRepository doctors;
    private final SpecializationRepository specializations;
    private final AuthService authService;
    private final NotificationService notifications;
    private final AuditService audit;

    public AdminService(UserRepository users, DoctorProfileRepository doctors, SpecializationRepository specializations,
                        AuthService authService, NotificationService notifications, AuditService audit) {
        this.users = users;
        this.doctors = doctors;
        this.specializations = specializations;
        this.authService = authService;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ------------------------------------------------------------ users
    @Transactional(readOnly = true)
    public PageResponse<AdminUserDto> users(Role role, Boolean active, VerificationStatus verification, String q,
                                            String sort, String dir, int page, int size) {
        Specification<UserAccount> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(root.get("role").in(Role.PATIENT, Role.DOCTOR)); // admins are never listed or editable here
            if (role != null) p.add(cb.equal(root.get("role"), role));
            if (active != null) p.add(cb.equal(root.get("active"), active));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("fullName")), like), cb.like(cb.lower(root.get("email")), like)));
            }
            if (verification != null) {
                var sub = query.subquery(Long.class);
                var dp = sub.from(DoctorProfile.class);
                sub.select(dp.get("id")).where(cb.equal(dp.get("user"), root), cb.equal(dp.get("verificationStatus"), verification));
                p.add(cb.exists(sub));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        String sortField = switch (sort == null ? "" : sort) {
            case "name" -> "fullName";
            case "email" -> "email";
            case "lastLogin" -> "lastLoginAt";
            default -> "createdAt";
        };
        Sort s = "asc".equalsIgnoreCase(dir) ? Sort.by(sortField).ascending() : Sort.by(sortField).descending();
        return PageResponse.of(users.findAll(spec, PageRequest.of(Math.max(page, 0), AuditService.clamp(size), s)).map(this::toDto));
    }

    public AdminUserDto setActive(CurrentUser admin, Long userId, boolean active) {
        UserAccount u = users.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));
        if (u.getRole() == Role.ADMIN) throw ApiException.forbidden("Administrator accounts cannot be changed here.");
        if (u.getId().equals(admin.id())) throw ApiException.badRequest("You cannot deactivate your own account.");
        u.setActive(active);
        if (!active) u.setTokenVersion(u.getTokenVersion() + 1); // kick out existing sessions
        audit.log(admin, active ? "USER_ACTIVATED" : "USER_DEACTIVATED", "UserAccount", u.getId(), u.getEmail());
        return toDto(u);
    }

    // ------------------------------------------------------------ doctors
    public AdminUserDto createDoctor(CurrentUser admin, RegisterDoctorRequest r) {
        DoctorProfile d = authService.createDoctor(r, VerificationStatus.VERIFIED, false);
        audit.log(admin, "DOCTOR_CREATED", "DoctorProfile", d.getId(), d.getUser().getEmail() + " (verified on creation)");
        notifications.notify(d.getUser(), "ACCOUNT_CREATED", "Welcome to MediBook",
                "Your doctor account was created and verified by an administrator. Set up your availability to start receiving bookings.", null);
        return toDto(d.getUser());
    }

    public AdminUserDto verify(CurrentUser admin, Long doctorProfileId, VerificationRequest r) {
        if (r.status() == VerificationStatus.PENDING) throw ApiException.badRequest("Choose VERIFIED or REJECTED.");
        DoctorProfile d = doctors.findById(doctorProfileId).orElseThrow(() -> ApiException.notFound("Doctor not found"));
        d.setVerificationStatus(r.status());
        d.setVerificationNote(r.note() == null || r.note().isBlank() ? null : r.note().trim());
        audit.log(admin, "DOCTOR_" + r.status().name(), "DoctorProfile", d.getId(),
                d.getUser().getEmail() + (d.getVerificationNote() == null ? "" : ": " + d.getVerificationNote()));
        notifications.notify(d.getUser(), "DOCTOR_VERIFICATION",
                r.status() == VerificationStatus.VERIFIED ? "You are verified" : "Verification not approved",
                r.status() == VerificationStatus.VERIFIED
                        ? "Your profile is now visible to patients. Make sure your availability is set."
                        : "Your application was not approved." + (d.getVerificationNote() == null ? "" : " Note: " + d.getVerificationNote()), null);
        return toDto(d.getUser());
    }

    // ------------------------------------------------------------ specializations
    @Transactional(readOnly = true)
    public List<SpecializationDto> specializations() {
        return specializations.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(s -> new SpecializationDto(s.getId(), s.getName(), s.getDescription(), doctors.countBySpecializationId(s.getId())))
                .toList();
    }

    public SpecializationDto createSpecialization(CurrentUser admin, SpecializationRequest r) {
        String name = r.name().trim();
        if (specializations.findByNameIgnoreCase(name).isPresent()) throw ApiException.conflict("That specialization already exists.");
        Specialization s = new Specialization();
        s.setName(name);
        s.setDescription(r.description());
        specializations.save(s);
        audit.log(admin, "SPECIALIZATION_CREATED", "Specialization", s.getId(), name);
        return new SpecializationDto(s.getId(), s.getName(), s.getDescription(), 0);
    }

    public SpecializationDto updateSpecialization(CurrentUser admin, Long id, SpecializationRequest r) {
        Specialization s = specializations.findById(id).orElseThrow(() -> ApiException.notFound("Specialization not found"));
        String name = r.name().trim();
        specializations.findByNameIgnoreCase(name).filter(o -> !o.getId().equals(id))
                .ifPresent(o -> { throw ApiException.conflict("That specialization already exists."); });
        s.setName(name);
        s.setDescription(r.description());
        audit.log(admin, "SPECIALIZATION_UPDATED", "Specialization", id, name);
        return new SpecializationDto(s.getId(), s.getName(), s.getDescription(), doctors.countBySpecializationId(id));
    }

    public void deleteSpecialization(CurrentUser admin, Long id) {
        Specialization s = specializations.findById(id).orElseThrow(() -> ApiException.notFound("Specialization not found"));
        if (doctors.countBySpecializationId(id) > 0) {
            throw ApiException.conflict("This specialization is used by doctors. Reassign them before deleting it.");
        }
        specializations.delete(s);
        audit.log(admin, "SPECIALIZATION_DELETED", "Specialization", id, s.getName());
    }

    private AdminUserDto toDto(UserAccount u) {
        DoctorProfile dp = u.getRole() == Role.DOCTOR ? doctors.findByUserId(u.getId()).orElse(null) : null;
        return new AdminUserDto(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getRole(), u.isActive(), u.isDemo(),
                u.getCreatedAt(), u.getLastLoginAt(), dp == null ? null : dp.getId(),
                dp == null ? null : dp.getVerificationStatus(), dp == null ? null : dp.getSpecialization().getName());
    }
}
