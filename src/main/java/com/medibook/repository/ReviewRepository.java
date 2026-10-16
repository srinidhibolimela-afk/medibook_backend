package com.medibook.repository;

import com.medibook.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long>, JpaSpecificationExecutor<Review> {
    boolean existsByAppointmentId(Long appointmentId);
    Page<Review> findByDoctorIdAndHiddenFalseOrderByCreatedAtDesc(Long doctorId, Pageable pageable);
    List<Review> findByAppointmentIdIn(Collection<Long> appointmentIds);

    /** [doctorId, average rating, count] for every doctor, ignoring moderated (hidden) reviews. */
    @Query("select r.doctor.id, avg(r.rating), count(r) from Review r where r.hidden = false group by r.doctor.id")
    List<Object[]> ratingStats();

    /** [rating, count] for one doctor - used for the star histogram. */
    @Query("select r.rating, count(r) from Review r where r.doctor.id = :doctorId and r.hidden = false group by r.rating")
    List<Object[]> ratingHistogram(@Param("doctorId") Long doctorId);
}
