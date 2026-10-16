package com.medibook.controller;

import com.medibook.dto.Dtos.DoctorCard;
import com.medibook.security.CurrentUser;
import com.medibook.service.FavouriteService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/favourites")
public class FavouriteController {
    private final FavouriteService favourites;

    public FavouriteController(FavouriteService favourites) { this.favourites = favourites; }

    @GetMapping
    public List<DoctorCard> list(@AuthenticationPrincipal CurrentUser me) { return favourites.list(me); }

    @GetMapping("/ids")
    public List<Long> ids(@AuthenticationPrincipal CurrentUser me) { return favourites.ids(me); }

    @PostMapping("/{doctorId}")
    public ResponseEntity<Void> add(@AuthenticationPrincipal CurrentUser me, @PathVariable Long doctorId) {
        favourites.add(me, doctorId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{doctorId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal CurrentUser me, @PathVariable Long doctorId) {
        favourites.remove(me, doctorId);
        return ResponseEntity.noContent().build();
    }
}
