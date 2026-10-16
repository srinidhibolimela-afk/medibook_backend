package com.medibook.service;

import com.medibook.dto.Dtos.DoctorCard;
import com.medibook.entity.DoctorProfile;
import com.medibook.entity.FavouriteDoctor;
import com.medibook.entity.UserAccount;
import com.medibook.exception.ApiException;
import com.medibook.repository.FavouriteDoctorRepository;
import com.medibook.repository.UserRepository;
import com.medibook.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class FavouriteService {
    private final FavouriteDoctorRepository favourites;
    private final UserRepository users;
    private final DoctorService doctorService;

    public FavouriteService(FavouriteDoctorRepository favourites, UserRepository users, DoctorService doctorService) {
        this.favourites = favourites;
        this.users = users;
        this.doctorService = doctorService;
    }

    public void add(CurrentUser me, Long doctorId) {
        DoctorProfile d = doctorService.publicDoctor(doctorId);
        if (favourites.existsByPatientIdAndDoctorId(me.id(), doctorId)) return; // idempotent
        UserAccount patient = users.findById(me.id()).orElseThrow(() -> ApiException.unauthorized("Account not found"));
        FavouriteDoctor f = new FavouriteDoctor();
        f.setPatient(patient);
        f.setDoctor(d);
        favourites.save(f);
    }

    public void remove(CurrentUser me, Long doctorId) {
        favourites.findByPatientIdAndDoctorId(me.id(), doctorId).ifPresent(favourites::delete);
    }

    @Transactional(readOnly = true)
    public List<Long> ids(CurrentUser me) {
        return favourites.findByPatientIdOrderByCreatedAtDesc(me.id()).stream().map(f -> f.getDoctor().getId()).toList();
    }

    @Transactional(readOnly = true)
    public List<DoctorCard> list(CurrentUser me) {
        List<DoctorProfile> docs = favourites.findByPatientIdOrderByCreatedAtDesc(me.id()).stream()
                .map(FavouriteDoctor::getDoctor)
                .filter(d -> d.getVerificationStatus() == com.medibook.entity.VerificationStatus.VERIFIED && d.getUser().isActive())
                .toList();
        return doctorService.cards(docs);
    }
}
