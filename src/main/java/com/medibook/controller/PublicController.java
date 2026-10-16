package com.medibook.controller;

import com.medibook.config.AppProperties;
import com.medibook.dto.Dtos.*;
import com.medibook.service.AvailabilityService;
import com.medibook.service.DoctorService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Everything a visitor can see without logging in. Only verified, active doctors are ever exposed. */
@RestController
@RequestMapping("/api/public")
public class PublicController {
    private final DoctorService doctors;
    private final AvailabilityService availability;
    private final AppProperties props;

    public PublicController(DoctorService doctors, AvailabilityService availability, AppProperties props) {
        this.doctors = doctors;
        this.availability = availability;
        this.props = props;
    }

    @GetMapping("/config")
    public PublicConfig config() { return new PublicConfig("MediBook", props.seed().demoData()); }

    @GetMapping("/specializations")
    public List<SpecializationDto> specializations() { return doctors.specializations(); }

    @GetMapping("/doctors")
    public PageResponse<DoctorCard> search(@RequestParam(required = false) String q,
                                           @RequestParam(required = false) Long specializationId,
                                           @RequestParam(required = false) String location,
                                           @RequestParam(required = false) BigDecimal minFee,
                                           @RequestParam(required = false) BigDecimal maxFee,
                                           @RequestParam(required = false) Integer minExperience,
                                           @RequestParam(required = false) Double minRating,
                                           @RequestParam(required = false) Integer availableWithinDays,
                                           @RequestParam(required = false) String sort,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "9") int size) {
        return doctors.search(new DoctorService.Search(q, specializationId, location, minFee, maxFee, minExperience,
                minRating, availableWithinDays, sort), page, size);
    }

    @GetMapping("/doctors/{id}")
    public DoctorDetail detail(@PathVariable Long id) { return doctors.detail(id); }

    @GetMapping("/doctors/{id}/slots")
    public List<SlotDto> slots(@PathVariable Long id, @RequestParam LocalDate date) {
        doctors.publicDoctor(id);
        return availability.slots(id, date);
    }

    @GetMapping("/doctors/{id}/available-dates")
    public List<LocalDate> availableDates(@PathVariable Long id, @RequestParam(defaultValue = "30") int days) {
        doctors.publicDoctor(id);
        return availability.availableDates(id, days);
    }

    @GetMapping("/doctors/{id}/reviews")
    public PageResponse<ReviewDto> reviews(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "5") int size) {
        return doctors.publicReviews(id, page, size);
    }
}
