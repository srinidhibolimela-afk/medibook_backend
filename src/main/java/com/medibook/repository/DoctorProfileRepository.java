package com.medibook.repository;

import com.medibook.entity.DoctorProfile;
import com.medibook.entity.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;

public interface DoctorProfileRepository extends JpaRepository<DoctorProfile, Long>, JpaSpecificationExecutor<DoctorProfile> {
    Optional<DoctorProfile> findByUserId(Long userId);
    long countByVerificationStatus(VerificationStatus status);
    long countBySpecializationId(Long specializationId);
}
