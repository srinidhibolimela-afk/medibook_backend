package com.medibook.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "appointments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_appt_reference", columnNames = "reference"),
                // THE double-booking guard. slotKey is non-null only while the appointment holds the slot,
                // and MySQL/H2 allow many NULLs in a unique index, so cancelled/rejected slots become free again.
                @UniqueConstraint(name = "uk_appt_slot", columnNames = "slotKey")
        },
        indexes = {
                @Index(name = "idx_appt_doctor_date", columnList = "doctor_id, appointmentDate"),
                @Index(name = "idx_appt_patient", columnList = "patient_id"),
                @Index(name = "idx_appt_status", columnList = "status")
        })
@Getter @Setter
public class Appointment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30) private String reference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private UserAccount patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private DoctorProfile doctor;

    @Column(nullable = false) private LocalDate appointmentDate;
    @Column(nullable = false) private LocalTime startTime;
    @Column(nullable = false) private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppointmentStatus status = AppointmentStatus.PENDING;

    @Column(nullable = false, length = 500) private String reason;
    @Column(length = 500) private String cancelReason;

    /** Private to the treating doctor. Never included in any patient or admin DTO. */
    @Column(length = 4000) private String visitNotes;

    /** Fee is copied at booking time so later fee changes do not rewrite history or revenue reports. */
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal feeAtBooking;

    @Column(length = 80) private String slotKey;

    @Column(nullable = false) private int rescheduleCount = 0;
    @Column(nullable = false) private boolean reminderSent = false;

    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    /** Optimistic locking: two people changing the same appointment at once -> second one gets a 409. */
    @Version
    private long version;

    public static String buildSlotKey(Long doctorId, LocalDate date, LocalTime time) {
        return doctorId + "|" + date + "|" + time;
    }

    /** Keep slotKey consistent with status. Call after every status/slot change. */
    public void syncSlotKey() {
        this.slotKey = AppointmentStatus.SLOT_HOLDING.contains(status)
                ? buildSlotKey(doctor.getId(), appointmentDate, startTime) : null;
        this.updatedAt = Instant.now();
    }
}
