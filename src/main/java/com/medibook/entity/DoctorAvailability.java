package com.medibook.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.DayOfWeek;
import java.time.LocalTime;

/** Weekly working hours. One row per doctor per weekday; slots are generated from it on demand. */
@Entity
@Table(name = "doctor_availability",
        uniqueConstraints = @UniqueConstraint(name = "uk_avail_doctor_day", columnNames = {"doctor_id", "dayOfWeek"}))
@Getter @Setter
public class DoctorAvailability {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private DoctorProfile doctor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false) private LocalTime startTime;
    @Column(nullable = false) private LocalTime endTime;
    @Column(nullable = false) private int slotMinutes = 30;
    @Column(nullable = false) private boolean active = true;
}
