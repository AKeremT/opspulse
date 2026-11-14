package com.opspulse.statuspage.controller;

import com.opspulse.statuspage.dto.PublicStatusResponse;
import com.opspulse.statuspage.service.StatusPageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated public endpoint serving workspace status pages.
 */
@RestController
@RequestMapping("/api/v1/public/status")
public class PublicStatusController {

    private final StatusPageService statusPageService;

    public PublicStatusController(StatusPageService statusPageService) {
        this.statusPageService = statusPageService;
    }

    @GetMapping("/{slug}")
    public ResponseEntity<PublicStatusResponse> getPublicStatus(@PathVariable String slug) {
        return ResponseEntity.ok(statusPageService.getPublicStatus(slug));
    }
}
