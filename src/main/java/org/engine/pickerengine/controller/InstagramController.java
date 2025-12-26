package org.engine.pickerengine.controller;

import org.engine.pickerengine.dto.InstagramKeywordPromptResponse;
import org.engine.pickerengine.dto.InstagramKeywordRequest;
import org.engine.pickerengine.dto.InstagramKeywordResponse;
import org.engine.pickerengine.dto.InstagramProfile;
import org.engine.pickerengine.dto.InstagramProfileWithPosts;
import org.engine.pickerengine.dto.InstagramRequest;
import org.engine.pickerengine.service.InstagramDmService;
import org.engine.pickerengine.service.InstagramKeywordService;
import org.engine.pickerengine.service.InstagramService;
import org.engine.pickerengine.service.InstagramPromptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.engine.pickerengine.dto.InstagramDmRequest;
import org.engine.pickerengine.dto.InstagramDmResponse;

import java.util.List;

@RestController
@RequestMapping("/instagram")
public class InstagramController {

    private final InstagramService instagramService;
    private final InstagramKeywordService instagramKeywordService;
    private final InstagramPromptService instagramPromptService;
    private final InstagramDmService instagramDmService;

    public InstagramController(
            InstagramService instagramService,
            InstagramKeywordService instagramKeywordService,
            InstagramPromptService instagramPromptService,
            InstagramDmService instagramDmService) {
        this.instagramService = instagramService;
        this.instagramKeywordService = instagramKeywordService;
        this.instagramPromptService = instagramPromptService;
        this.instagramDmService = instagramDmService;
    }

    @PostMapping("/profiles")
    public List<InstagramProfile> getProfiles(@RequestBody InstagramRequest request) {
        return instagramService.fetchProfiles(request.userId());
    }

    @PostMapping("/profile-details")
    public InstagramProfileWithPosts getProfileDetails(@RequestBody InstagramRequest request) {
        return instagramService.fetchProfileWithPosts(request.userId());
    }

    @PostMapping("/profile-cache")
    public InstagramProfileWithPosts getCachedProfileDetails(@RequestBody InstagramRequest request) {
        return instagramService.fetchCachedProfileWithPosts(request.userId());
    }

    @PostMapping("/extract-keywords")
    public InstagramKeywordResponse getKeywords(@RequestBody InstagramKeywordRequest request) {
        return instagramKeywordService.extractKeywords(
                request.userId(),
                request.version(),
                request.ignoreCacheOrDefault());
    }

    @PostMapping("/generate-dm")
    public InstagramDmResponse generateDm(@RequestBody InstagramDmRequest request) {
        return instagramDmService.generateDm(
                request.userId(),
                request.version(),
                request.ignoreCacheOrDefault());
    }

    @PostMapping("/keyword-prompt")
    public InstagramKeywordPromptResponse getKeywordPrompt(@RequestBody InstagramKeywordRequest request) {
        return instagramKeywordService.buildPromptPreview(request.userId(), request.version());
    }

    @GetMapping("/keyword-versions")
    public List<String> getKeywordVersions() {
        return instagramPromptService.listVersions();
    }
}
