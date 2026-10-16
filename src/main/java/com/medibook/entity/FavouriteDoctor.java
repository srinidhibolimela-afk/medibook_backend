package com.medibook.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "favourite_doctors",
        uniqueConstraints = @UniqueConstraint(name = "uk_fav_patient_doctor", columnNames = {"patient_id", "doctor_id"}))
@Getter @Setter
public class FavouriteDoctor {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private UserAccount patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private DoctorProfile doctor;

    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
