package com.medibook.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "reviews",
        // One review per appointment, enforced by the database as well as the service.
        uniqueConstraints = @UniqueConstraint(name = "uk_review_appointment", columnNames = "appointment_id"),
        indexes = @Index(name = "idx_review_doctor", columnList = "doctor_id, hidden"))
@Getter @Setter
public class Review {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private UserAccount patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private DoctorProfile doctor;

    @Column(nullable = false) private int rating;
    @Column(length = 1000) private String comment;
    @Column(nullable = false) private boolean hidden = false;
    @Column(length = 255) private String hiddenReason;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
