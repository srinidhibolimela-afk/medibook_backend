package com.medibook.repository;

import com.medibook.entity.DoctorAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DoctorAvailabilityRepository extends JpaRepository<DoctorAvailability, Long> {
    List<DoctorAvailability> findByDoctorIdOrderByDayOfWeek(Long doctorId);
    List<DoctorAvailability> findByActiveTrue();
    void deleteByDoctorId(Long doctorId);
}
