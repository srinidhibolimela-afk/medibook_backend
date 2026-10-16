package com.medibook.dto;

import com.medibook.entity.Role;
import com.medibook.entity.VerificationStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Every request/response shape of the API lives here as a Java record.
 * Entities are never returned directly, so password hashes and private notes cannot leak by accident.
 */
public final class Dtos {
    private Dtos() {}

    public static final String PASSWORD_REGEX = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$";
    public static final String PASSWORD_MSG = "Password must be 8-64 characters and include a letter and a number.";
    public static final String PHONE_REGEX = "^[0-9+\\-\\s]{7,20}$";
    public static final String PHONE_MSG = "Enter a valid phone number (digits, +, - and spaces).";

    // ---------------- generic ----------------
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> p) {
            return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
        }
        public static <T> PageResponse<T> ofList(List<T> all, int page, int size) {
            int from = Math.min(page * size, all.size());
            int to = Math.min(from + size, all.size());
            int pages = size == 0 ? 0 : (int) Math.ceil(all.size() / (double) size);
            return new PageResponse<>(all.subList(from, to), page, size, all.size(), pages);
        }
    }

    public record MessageResponse(String message) {}

    // ---------------- auth ----------------
    public record LoginRequest(@NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email,
                               @NotBlank(message = "Password is required") String password,
                               @NotNull(message = "Portal is required") Role portal) {}

    public record RegisterPatientRequest(
            @NotBlank(message = "Full name is required") @Size(max = 120) String fullName,
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") @Size(max = 150) String email,
            @NotBlank(message = "Password is required") @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MSG) String password,
            @Pattern(regexp = PHONE_REGEX, message = PHONE_MSG) String phone,
            @Past(message = "Date of birth must be in the past") LocalDate dateOfBirth,
            @Size(max = 20) String gender) {}

    /** Used for public doctor applications AND for admin-created doctors. */
    public record RegisterDoctorRequest(
            @NotBlank(message = "Full name is required") @Size(max = 120) String fullName,
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") @Size(max = 150) String email,
            @NotBlank(message = "Password is required") @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MSG) String password,
            @Pattern(regexp = PHONE_REGEX, message = PHONE_MSG) String phone,
            @NotNull(message = "Choose a specialization") Long specializationId,
            @NotBlank(message = "Qualifications are required") @Size(max = 255) String qualifications,
            @NotNull(message = "Experience is required") @Min(0) @Max(70) Integer experienceYears,
            @NotNull(message = "Consultation fee is required") @DecimalMin(value = "0.0") @DecimalMax("100000") BigDecimal consultationFee,
            @NotBlank(message = "Clinic or hospital is required") @Size(max = 150) String clinicName,
            @NotBlank(message = "Location is required") @Size(max = 100) String location,
            @Size(max = 2000) String bio) {}

    public record UserView(Long id, String email, String fullName, String phone, Role role, boolean demo,
                           Long doctorId, VerificationStatus verificationStatus) {}

    public record AuthResponse(String token, long expiresInSeconds, UserView user) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "Current password is required") String currentPassword,
            @NotBlank(message = "New password is required") @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MSG) String newPassword) {}

    // ---------------- doctors / directory ----------------
    public record SpecializationDto(Long id, String name, String description, long doctorCount) {}

    public record DoctorCard(Long id, String name, Long specializationId, String specialization, String qualifications,
                             int experienceYears, BigDecimal consultationFee, String clinicName, String location,
                             String photoUrl, VerificationStatus verificationStatus, Double averageRating,
                             long reviewCount, LocalDateTime nextAvailable, boolean demo) {}

    public record AvailabilityDto(@NotNull DayOfWeek dayOfWeek, @NotNull LocalTime startTime, @NotNull LocalTime endTime,
                                  @Min(10) @Max(120) int slotMinutes, boolean active) {}

    public record DoctorDetail(DoctorCard card, String bio, Map<Integer, Long> ratingHistogram,
                               List<AvailabilityDto> schedule) {}

    public record SlotDto(String time, boolean available) {}

    public record DoctorSelfView(Long id, String fullName, String email, String phone, Long specializationId,
                                 String specialization, String qualifications, int experienceYears,
                                 BigDecimal consultationFee, String clinicName, String location, String bio,
                                 String photoUrl, VerificationStatus verificationStatus, String verificationNote) {}

    public record UpdateDoctorProfileRequest(
            @NotBlank @Size(max = 120) String fullName,
            @Pattern(regexp = PHONE_REGEX, message = PHONE_MSG) String phone,
            @NotNull Long specializationId,
            @NotBlank @Size(max = 255) String qualifications,
            @NotNull @Min(0) @Max(70) Integer experienceYears,
            @NotNull @DecimalMin("0.0") @DecimalMax("100000") BigDecimal consultationFee,
            @NotBlank @Size(max = 150) String clinicName,
            @NotBlank @Size(max = 100) String location,
            @Size(max = 2000) String bio,
            @Pattern(regexp = "^(https?://\\S{3,480})?$", message = "Photo must be an http(s) link") String photoUrl) {}

    public record AvailabilityUpdateResult(List<AvailabilityDto> schedule, int affectedAppointments) {}

    // ---------------- patient ----------------
    public record PatientProfileDto(
            @NotBlank @Size(max = 120) String fullName,
            @Pattern(regexp = PHONE_REGEX, message = PHONE_MSG) String phone,
            @Past LocalDate dateOfBirth,
            @Size(max = 20) String gender,
            @Size(max = 5) String bloodGroup,
            @Size(max = 1000) String allergies,
            @Size(max = 1000) String medicalNotes,
            @Size(max = 150) String emergencyContact,
            @Size(max = 255) String address,
            String email) {}

    /** What a treating doctor may see about a patient they have an appointment with. */
    public record PatientInfo(String fullName, Integer age, String gender, String bloodGroup, String allergies,
                              String medicalNotes, String emergencyContact) {}

    // ---------------- appointments ----------------
    public record BookRequest(@NotNull(message = "Doctor is required") Long doctorId,
                              @NotNull(message = "Date is required") LocalDate date,
                              @NotNull(message = "Time slot is required") LocalTime time,
                              @NotBlank(message = "Please tell the doctor why you are visiting")
                              @Size(min = 3, max = 500, message = "Reason must be 3-500 characters") String reason) {}

    public record RescheduleRequest(@NotNull LocalDate date, @NotNull LocalTime time) {}

    public record ReasonRequest(@Size(max = 500) String reason) {}

    public record CompleteRequest(@Size(max = 4000) String visitNotes) {}

    public record NotesRequest(@Size(max = 4000) String visitNotes) {}

    public record AppointmentDto(Long id, String reference, String status, LocalDate date, LocalTime startTime,
                                 LocalTime endTime, String reason, String cancelReason, BigDecimal fee,
                                 int rescheduleCount, boolean canCancel, boolean canReschedule, boolean reviewed,
                                 Long doctorId, String doctorName, String specialization, String clinicName,
                                 String location, String doctorPhotoUrl,
                                 Long patientId, String patientName, String patientPhone,
                                 PatientInfo patientInfo, String visitNotes, Instant createdAt) {}

    public record ReceiptDto(AppointmentDto appointment, String patientEmail, String doctorQualifications,
                             Instant issuedAt, String paymentNote) {}

    // ---------------- reviews / notifications ----------------
    public record ReviewRequest(@NotNull(message = "Appointment is required") Long appointmentId,
                                @Min(value = 1, message = "Rating must be 1-5") @Max(value = 5, message = "Rating must be 1-5") int rating,
                                @Size(max = 1000) String comment) {}

    public record ReviewDto(Long id, int rating, String comment, String patientName, Long doctorId, String doctorName,
                            String appointmentReference, boolean hidden, String hiddenReason, Instant createdAt) {}

    public record HideReviewRequest(boolean hidden, @Size(max = 255) String reason) {}

    public record NotificationDto(Long id, String type, String title, String message, Long appointmentId,
                                  boolean read, Instant createdAt) {}

    public record UnreadCount(long count) {}

    // ---------------- dashboards / admin ----------------
    public record SeriesPoint(String label, long appointments, BigDecimal revenue) {}

    public record NameCount(String name, long count) {}

    public record AuditLogDto(Long id, String actorEmail, String actorRole, String action, String targetType,
                              Long targetId, String details, Instant createdAt) {}

    public record DoctorDashboard(Map<String, Long> byStatus, long today, List<SeriesPoint> daily, Double averageRating,
                                  long reviewCount, List<AppointmentDto> upcoming) {}

    public record AdminDashboard(Map<String, Long> totals, Map<String, Long> byStatus, BigDecimal revenue,
                                 List<SeriesPoint> daily, List<SeriesPoint> weekly, List<SeriesPoint> monthly,
                                 List<NameCount> bySpecialization, List<AuditLogDto> recentActivity) {}

    public record AdminUserDto(Long id, String email, String fullName, String phone, Role role, boolean active, boolean demo,
                               Instant createdAt, Instant lastLoginAt, Long doctorId, VerificationStatus verificationStatus,
                               String specialization) {}

    public record ActiveRequest(boolean active) {}

    public record VerificationRequest(@NotNull(message = "Status is required") VerificationStatus status,
                                      @Size(max = 255) String note) {}

    public record SpecializationRequest(@NotBlank(message = "Name is required") @Size(max = 80) String name,
                                        @Size(max = 255) String description) {}

    public record RevenueRow(Long doctorId, String doctorName, String specialization, long completedVisits, BigDecimal revenue) {}

    public record SearchResult(List<DoctorCard> doctors, List<AppointmentDto> appointments, List<AdminUserDto> users) {}

    public record PublicConfig(String appName, boolean demoMode) {}
}
