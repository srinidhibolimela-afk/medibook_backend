package com.medibook.service;

import com.medibook.dto.Dtos.*;
import com.medibook.entity.*;
import com.medibook.exception.ApiException;
import com.medibook.repository.*;
import com.medibook.security.CurrentUser;
import com.medibook.security.JwtService;
import com.medibook.util.Mapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Transactional
public class AuthService {
    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_SECONDS = 15 * 60;

    private final UserRepository users;
    private final PatientProfileRepository patients;
    private final DoctorProfileRepository doctors;
    private final SpecializationRepository specs;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final Mapper mapper;
    private final AuditService audit;
    private final NotificationService notifications;
    private final String dummyHash;
    /** In-memory brute-force guard (per email). Resets on restart; documented limitation. */
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public AuthService(UserRepository users, PatientProfileRepository patients, DoctorProfileRepository doctors,
                       SpecializationRepository specs, PasswordEncoder encoder, JwtService jwt, Mapper mapper,
                       AuditService audit, NotificationService notifications) {
        this.users = users;
        this.patients = patients;
        this.doctors = doctors;
        this.specs = specs;
        this.encoder = encoder;
        this.jwt = jwt;
        this.mapper = mapper;
        this.audit = audit;
        this.notifications = notifications;
        this.dummyHash = encoder.encode("not-a-real-password-1");
    }

    // ------------------------------------------------------------ registration
    public AuthResponse registerPatient(RegisterPatientRequest r) {
        String email = normalise(r.email());
        ensureEmailFree(email);
        UserAccount u = newUser(email, r.password(), r.fullName(), r.phone(), Role.PATIENT);
        users.save(u);
        PatientProfile p = new PatientProfile();
        p.setUser(u);
        p.setDateOfBirth(r.dateOfBirth());
        p.setGender(r.gender());
        patients.save(p);
        return respond(u, null);
    }

    /** Public doctor application. Starts as PENDING: not listed and not bookable until an admin verifies. */
    public AuthResponse registerDoctor(RegisterDoctorRequest r) {
        DoctorProfile dp = createDoctor(r, VerificationStatus.PENDING, false);
        notifications.notifyAdmins("DOCTOR_APPLICATION", "New doctor application",
                dp.getUser().getFullName() + " applied to join as " + dp.getSpecialization().getName() + ".");
        return respond(dp.getUser(), dp);
    }

    /** Shared by public applications, admin creation and the seeder. */
    public DoctorProfile createDoctor(RegisterDoctorRequest r, VerificationStatus status, boolean demo) {
        String email = normalise(r.email());
        ensureEmailFree(email);
        Specialization spec = specs.findById(r.specializationId())
                .orElseThrow(() -> ApiException.badRequest("Selected specialization does not exist"));
        UserAccount u = newUser(email, r.password(), r.fullName(), r.phone(), Role.DOCTOR);
        u.setDemo(demo);
        users.save(u);
        DoctorProfile d = new DoctorProfile();
        d.setUser(u);
        d.setSpecialization(spec);
        d.setQualifications(r.qualifications());
        d.setExperienceYears(r.experienceYears());
        d.setConsultationFee(r.consultationFee());
        d.setClinicName(r.clinicName());
        d.setLocation(r.location());
        d.setBio(r.bio());
        d.setVerificationStatus(status);
        return doctors.save(d);
    }

    // ------------------------------------------------------------ login / session
    public AuthResponse login(LoginRequest r) {
        String email = normalise(r.email());
        checkNotLocked(email);
        UserAccount u = users.findByEmailIgnoreCase(email).orElse(null);
        // Always run one BCrypt comparison so response time does not reveal whether the email exists.
        boolean ok = encoder.matches(r.password(), u == null ? dummyHash : u.getPasswordHash()) && u != null;
        if (!ok) {
            recordFailure(email);
            throw ApiException.unauthorized("Invalid email or password.");
        }
        if (!u.isActive()) throw ApiException.forbidden("This account has been deactivated. Please contact support.");
        if (u.getRole() != r.portal()) {
            throw ApiException.forbidden("This account belongs to the " + u.getRole().name().toLowerCase()
                    + " portal. Please use the " + u.getRole().name().toLowerCase() + " login page.");
        }
        failures.remove(email);
        u.setLastLoginAt(Instant.now());
        if (u.getRole() == Role.ADMIN) audit.log(u.getEmail(), "ADMIN", "ADMIN_LOGIN", "UserAccount", u.getId(), "Admin signed in");
        return respond(u, doctorOf(u));
    }

    /** Bumping tokenVersion invalidates every token issued earlier, so logout really logs out. */
    public void logout(CurrentUser me) {
        users.findById(me.id()).ifPresent(u -> u.setTokenVersion(u.getTokenVersion() + 1));
    }

    @Transactional(readOnly = true)
    public UserView me(CurrentUser me) {
        UserAccount u = users.findById(me.id()).orElseThrow(() -> ApiException.unauthorized("Account not found"));
        return mapper.userView(u, doctorOf(u));
    }

    public AuthResponse changePassword(CurrentUser me, ChangePasswordRequest r) {
        UserAccount u = users.findById(me.id()).orElseThrow(() -> ApiException.unauthorized("Account not found"));
        if (!encoder.matches(r.currentPassword(), u.getPasswordHash())) {
            throw ApiException.badRequest("Current password is incorrect.");
        }
        if (encoder.matches(r.newPassword(), u.getPasswordHash())) {
            throw ApiException.badRequest("New password must be different from the current one.");
        }
        u.setPasswordHash(encoder.encode(r.newPassword()));
        u.setTokenVersion(u.getTokenVersion() + 1); // signs out other devices
        if (u.getRole() == Role.ADMIN) audit.log(me, "ADMIN_PASSWORD_CHANGED", "UserAccount", u.getId(), null);
        return respond(u, doctorOf(u));
    }

    // ------------------------------------------------------------ helpers
    private UserAccount newUser(String email, String password, String name, String phone, Role role) {
        UserAccount u = new UserAccount();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(password));
        u.setFullName(name.trim());
        u.setPhone(phone == null || phone.isBlank() ? null : phone.trim());
        u.setRole(role);
        return u;
    }

    private void ensureEmailFree(String email) {
        if (users.existsByEmailIgnoreCase(email)) throw ApiException.conflict("An account with this email already exists.");
    }

    private DoctorProfile doctorOf(UserAccount u) {
        return u.getRole() == Role.DOCTOR ? doctors.findByUserId(u.getId()).orElse(null) : null;
    }

    private AuthResponse respond(UserAccount u, DoctorProfile dp) {
        return new AuthResponse(jwt.issue(u), jwt.expirationSeconds(), mapper.userView(u, dp));
    }

    private static String normalise(String email) { return email.trim().toLowerCase(); }

    private void checkNotLocked(String email) {
        Deque<Instant> q = failures.get(email);
        if (q == null) return;
        synchronized (q) {
            Instant cutoff = Instant.now().minusSeconds(WINDOW_SECONDS);
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
            if (q.size() >= MAX_FAILURES) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many failed sign-in attempts. Please wait 15 minutes and try again.");
            }
        }
    }

    private void recordFailure(String email) {
        Deque<Instant> q = failures.computeIfAbsent(email, k -> new ArrayDeque<>());
        synchronized (q) { q.addLast(Instant.now()); }
    }
}
