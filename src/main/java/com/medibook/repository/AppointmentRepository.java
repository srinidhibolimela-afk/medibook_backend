package com.medibook.repository;

import com.medibook.entity.Appointment;
import com.medibook.entity.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, Long>, JpaSpecificationExecutor<Appointment> {
    boolean existsBySlotKey(String slotKey);

    boolean existsByPatientIdAndAppointmentDateAndStartTimeAndStatusIn(Long patientId, LocalDate date, LocalTime time,
                                                                       Collection<AppointmentStatus> statuses);

    List<Appointment> findByDoctorIdAndAppointmentDateAndStatusIn(Long doctorId, LocalDate date,
                                                                  Collection<AppointmentStatus> statuses);

    List<Appointment> findByDoctorIdAndAppointmentDateBetweenAndStatusIn(Long doctorId, LocalDate from, LocalDate to,
                                                                         Collection<AppointmentStatus> statuses);

    List<Appointment> findByDoctorIdAndAppointmentDateBetween(Long doctorId, LocalDate from, LocalDate to);

    List<Appointment> findByAppointmentDateBetween(LocalDate from, LocalDate to);

    List<Appointment> findByDoctorIdAndStatusInAndAppointmentDateGreaterThanEqual(Long doctorId,
                                                                                   Collection<AppointmentStatus> statuses,
                                                                                   LocalDate from);

    List<Appointment> findByStatusInAndAppointmentDateGreaterThanEqual(Collection<AppointmentStatus> statuses, LocalDate from);

    List<Appointment> findByStatusAndReminderSentFalseAndAppointmentDateBetween(AppointmentStatus status, LocalDate from, LocalDate to);

    long countByDoctorId(Long doctorId);
}
