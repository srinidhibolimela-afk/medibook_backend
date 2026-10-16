package com.medibook.service;

import com.medibook.dto.Dtos.PatientProfileDto;
import com.medibook.entity.PatientProfile;
import com.medibook.entity.UserAccount;
import com.medibook.exception.ApiException;
import com.medibook.repository.PatientProfileRepository;
import com.medibook.repository.UserRepository;
import com.medibook.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PatientService {
    private final PatientProfileRepository profiles;
    private final UserRepository users;

    public PatientService(PatientProfileRepository profiles, UserRepository users) {
        this.profiles = profiles;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public PatientProfileDto get(CurrentUser me) {
        UserAccount u = users.findById(me.id()).orElseThrow(() -> ApiException.unauthorized("Account not found"));
        return toDto(u, profiles.findByUserId(me.id()).orElse(null));
    }

    public PatientProfileDto update(CurrentUser me, PatientProfileDto r) {
        UserAccount u = users.findById(me.id()).orElseThrow(() -> ApiException.unauthorized("Account not found"));
        PatientProfile p = profiles.findByUserId(me.id()).orElseGet(() -> {
            PatientProfile n = new PatientProfile();
            n.setUser(u);
            return n;
        });
        u.setFullName(r.fullName().trim());
        u.setPhone(blank(r.phone()));
        p.setDateOfBirth(r.dateOfBirth());
        p.setGender(blank(r.gender()));
        p.setBloodGroup(blank(r.bloodGroup()));
        p.setAllergies(blank(r.allergies()));
        p.setMedicalNotes(blank(r.medicalNotes()));
        p.setEmergencyContact(blank(r.emergencyContact()));
        p.setAddress(blank(r.address()));
        profiles.save(p);
        return toDto(u, p);
    }

    private PatientProfileDto toDto(UserAccount u, PatientProfile p) {
        return new PatientProfileDto(u.getFullName(), u.getPhone(),
                p == null ? null : p.getDateOfBirth(), p == null ? null : p.getGender(),
                p == null ? null : p.getBloodGroup(), p == null ? null : p.getAllergies(),
                p == null ? null : p.getMedicalNotes(), p == null ? null : p.getEmergencyContact(),
                p == null ? null : p.getAddress(), u.getEmail());
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
