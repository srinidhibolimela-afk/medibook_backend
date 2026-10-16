package com.medibook.controller;

import com.medibook.dto.Dtos.SearchResult;
import com.medibook.security.CurrentUser;
import com.medibook.service.SearchService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
public class SearchController {
    private final SearchService search;

    public SearchController(SearchService search) { this.search = search; }

    @GetMapping
    public SearchResult search(@AuthenticationPrincipal CurrentUser me, @RequestParam String q) { return search.search(me, q); }
}
