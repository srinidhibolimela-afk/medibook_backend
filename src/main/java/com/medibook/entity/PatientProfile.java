package com.medibook.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

/**
 * Optional medical information. Deliberately small: only data a doctor needs to prepare
 * for a visit. It is only exposed to the patient themself and to doctors who have an appointment with them.
 */
@Entity
@Table(name = "patient_profiles", uniqueConstraints = @UniqueConstraint(name = "uk_patient_user", columnNames = "user_id"))
@Getter @Setter
public class PatientProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    private LocalDate dateOfBirth;
    @Column(length = 20) private String gender;
    @Column(length = 5) private String bloodGroup;
    @Column(length = 1000) private String allergies;
    @Column(length = 1000) private String medicalNotes;
    @Column(length = 150) private String emergencyContact;
    @Column(length = 255) private String address;
}
