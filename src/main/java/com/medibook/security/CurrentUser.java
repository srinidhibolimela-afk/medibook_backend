package com.medibook.security;

import com.medibook.entity.Role;

/** The authenticated caller, available in controllers via @AuthenticationPrincipal. */
public record CurrentUser(Long id, String email, String fullName, Role role) {}
