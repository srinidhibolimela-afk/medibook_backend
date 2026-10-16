package com.medibook.controller;

import com.medibook.dto.Dtos.*;
import com.medibook.security.CurrentUser;
import com.medibook.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest r) { return auth.login(r); }

    @PostMapping("/register/patient")
    public ResponseEntity<AuthResponse> registerPatient(@Valid @RequestBody RegisterPatientRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.registerPatient(r));
    }

    /** Doctor application: account is created as PENDING until an admin verifies it. There is no admin registration endpoint. */
    @PostMapping("/register/doctor")
    public ResponseEntity<AuthResponse> registerDoctor(@Valid @RequestBody RegisterDoctorRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.registerDoctor(r));
    }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal CurrentUser me) { return auth.me(me); }

    @PostMapping("/logout")
    public MessageResponse logout(@AuthenticationPrincipal CurrentUser me) {
        auth.logout(me);
        return new MessageResponse("Signed out");
    }

    @PostMapping("/change-password")
    public AuthResponse changePassword(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody ChangePasswordRequest r) {
        return auth.changePassword(me, r);
    }
}
