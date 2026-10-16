package com.medibook.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity
@Table(name = "doctor_profiles",
        uniqueConstraints = @UniqueConstraint(name = "uk_doctor_user", columnNames = "user_id"),
        indexes = {
                @Index(name = "idx_doctor_spec", columnList = "specialization_id"),
                @Index(name = "idx_doctor_location", columnList = "location"),
                @Index(name = "idx_doctor_status", columnList = "verificationStatus")
        })
@Getter @Setter
public class DoctorProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "specialization_id", nullable = false)
    private Specialization specialization;

    @Column(nullable = false, length = 255) private String qualifications;
    @Column(nullable = false) private int experienceYears;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal consultationFee;
    @Column(nullable = false, length = 150) private String clinicName;
    @Column(nullable = false, length = 100) private String location;
    @Column(length = 2000) private String bio;
    @Column(length = 500) private String photoUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationStatus verificationStatus = VerificationStatus.PENDING;

    @Column(length = 255) private String verificationNote;
}
