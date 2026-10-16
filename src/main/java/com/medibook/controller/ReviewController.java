package com.medibook.controller;

import com.medibook.dto.Dtos.ReviewDto;
import com.medibook.dto.Dtos.ReviewRequest;
import com.medibook.security.CurrentUser;
import com.medibook.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reviews")
@PreAuthorize("hasRole('PATIENT')")
public class ReviewController {
    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) { this.reviews = reviews; }

    @PostMapping
    public ResponseEntity<ReviewDto> create(@AuthenticationPrincipal CurrentUser me, @Valid @RequestBody ReviewRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviews.create(me, r));
    }

    @GetMapping("/mine")
    public List<ReviewDto> mine(@AuthenticationPrincipal CurrentUser me) { return reviews.mine(me); }
}
