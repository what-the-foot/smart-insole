package com.smartinsole.recommendation.controller;

import com.smartinsole.recommendation.dto.RecommendationDtos.RecommendationDetailResponse;
import com.smartinsole.recommendation.service.RecommendationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {
    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @GetMapping("/{code}")
    public RecommendationDetailResponse get(@PathVariable String code) {
        return service.get(code);
    }
}
