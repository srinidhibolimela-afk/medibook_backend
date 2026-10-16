package com.medibook.controller;

import com.medibook.dto.Dtos.PatientProfileDto;
import com.medibook.security.CurrentUser;
import com.medibook.service.PatientService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patient")
public class PatientController {
    private final PatientService patients;

    public PatientController(PatientService patients) { this.patients = patients; }

    @GetMapping("/profile")
    public PatientProfileDto profile(@AuthenticationPrincipal CurrentUser me) { return patients.get(me); }

    @PutMapping("/profile")
    public PatientProfileDto update(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody PatientProfileDto r) {
        return patients.update(me, r);
    }
}
