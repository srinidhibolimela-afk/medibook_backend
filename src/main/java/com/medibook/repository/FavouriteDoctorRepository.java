package com.medibook.repository;

import com.medibook.entity.FavouriteDoctor;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FavouriteDoctorRepository extends JpaRepository<FavouriteDoctor, Long> {
    List<FavouriteDoctor> findByPatientIdOrderByCreatedAtDesc(Long patientId);
    Optional<FavouriteDoctor> findByPatientIdAndDoctorId(Long patientId, Long doctorId);
    boolean existsByPatientIdAndDoctorId(Long patientId, Long doctorId);
}
