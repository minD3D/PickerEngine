package org.engine.pickerengine.controller;

import org.engine.pickerengine.dto.InstagramKeywordPromptResponse;
import org.engine.pickerengine.dto.InstagramKeywordRequest;
import org.engine.pickerengine.dto.InstagramKeywordResponse;
import org.engine.pickerengine.dto.InstagramPricePromptRequest;
import org.engine.pickerengine.dto.InstagramPricePromptResponse;
import org.engine.pickerengine.dto.InstagramPriceRequest;
import org.engine.pickerengine.dto.InstagramProfile;
import org.engine.pickerengine.dto.InstagramProfileInsights;
import org.engine.pickerengine.dto.InstagramProfileWithPosts;
import org.engine.pickerengine.dto.InstagramRequest;
import org.engine.pickerengine.dto.InstagramSearchResponse;
import org.engine.pickerengine.dto.InstagramSearchUser;
import org.engine.pickerengine.dto.InstagramSearchUsersPage;
import org.engine.pickerengine.service.InstagramDmPromptService;
import org.engine.pickerengine.service.InstagramDmService;
import org.engine.pickerengine.service.InstagramKeywordService;
import org.engine.pickerengine.service.InstagramProfileInsightsService;
import org.engine.pickerengine.service.InstagramPricePromptService;
import org.engine.pickerengine.service.InstagramPriceService;
import org.engine.pickerengine.service.InstagramService;
import org.engine.pickerengine.service.InstagramInfluencerSyncService;
import org.engine.pickerengine.service.InstagramPromptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.engine.pickerengine.dto.InstagramDmPromptRequest;
import org.engine.pickerengine.dto.InstagramDmPromptResponse;
import org.engine.pickerengine.dto.InstagramDmKeywordsRequest;
import org.engine.pickerengine.dto.InstagramDmRequest;
import org.engine.pickerengine.dto.InstagramDmResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/instagram")
public class InstagramController {

    private final InstagramService instagramService;
    private final InstagramKeywordService instagramKeywordService;
    private final InstagramPromptService instagramPromptService;
    private final InstagramDmService instagramDmService;
    private final InstagramDmPromptService instagramDmPromptService;
    private final InstagramPriceService instagramPriceService;
    private final InstagramPricePromptService instagramPricePromptService;
    private final InstagramProfileInsightsService instagramProfileInsightsService;
    private final InstagramInfluencerSyncService instagramInfluencerSyncService;

    public InstagramController(
            InstagramService instagramService,
            InstagramKeywordService instagramKeywordService,
            InstagramPromptService instagramPromptService,
            InstagramDmService instagramDmService,
            InstagramDmPromptService instagramDmPromptService,
            InstagramPriceService instagramPriceService,
            InstagramPricePromptService instagramPricePromptService,
            InstagramProfileInsightsService instagramProfileInsightsService,
            InstagramInfluencerSyncService instagramInfluencerSyncService) {
        this.instagramService = instagramService;
        this.instagramKeywordService = instagramKeywordService;
        this.instagramPromptService = instagramPromptService;
        this.instagramDmService = instagramDmService;
        this.instagramDmPromptService = instagramDmPromptService;
        this.instagramPriceService = instagramPriceService;
        this.instagramPricePromptService = instagramPricePromptService;
        this.instagramProfileInsightsService = instagramProfileInsightsService;
        this.instagramInfluencerSyncService = instagramInfluencerSyncService;
    }

    @PostMapping("/profiles")
    public List<InstagramProfile> getProfiles(@RequestBody InstagramRequest request) {
        return instagramService.fetchProfiles(request.userId());
    }

    @PostMapping("/profile-details")
    public InstagramProfileWithPosts getProfileDetails(@RequestBody InstagramRequest request) {
        return instagramService.fetchProfileWithPosts(request.userId());
    }

    @GetMapping("/profile-by-username")
    public InstagramProfileInsights getProfileByUsername(@RequestParam("username") String username) {
        return instagramProfileInsightsService.fetchInsights(username);
    }

    @PostMapping("/influencer-sync/run-all")
    public Map<String, Object> runInfluencerSyncAll() {
        boolean started = instagramInfluencerSyncService.triggerRunAllAsync();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("started", started);
        payload.put("status", instagramInfluencerSyncService.getStatus());
        return payload;
    }

    @PostMapping("/influencer-sync/stop")
    public Map<String, Object> stopInfluencerSync() {
        boolean stopping = instagramInfluencerSyncService.requestStop();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stopping", stopping);
        payload.put("status", instagramInfluencerSyncService.getStatus());
        return payload;
    }

    @GetMapping("/influencer-sync/status")
    public InstagramInfluencerSyncService.SyncStatus getInfluencerSyncStatus() {
        return instagramInfluencerSyncService.getStatus();
    }

    @PostMapping("/profile-cache")
    public InstagramProfileWithPosts getCachedProfileDetails(@RequestBody InstagramRequest request) {
        return instagramService.fetchCachedProfileWithPosts(request.userId());
    }

    @GetMapping("/search/keyword/users/expand")
    public List<InstagramSearchUser> searchKeywordUsersExpanded(
            @RequestParam("q") String query,
            @RequestParam(value = "pages", defaultValue = "1") int pages) {
        return instagramService.searchKeywordUsersExpanded(query, pages);
    }

    @PostMapping("/extract-keywords")
    public InstagramKeywordResponse getKeywords(@RequestBody InstagramKeywordRequest request) {
        return instagramKeywordService.extractKeywords(
                request.userId(),
                request.version(),
                request.customPrompt(),
                request.ignoreCacheOrDefault());
    }

    @PostMapping("/generate-dm")
    public InstagramDmResponse generateDm(@RequestBody InstagramDmRequest request) {
        return instagramDmService.generateDm(
                request.userId(),
                request.version(),
                request.customKeywordPrompt(),
                request.dmVersionOrDefault(),
                request.customDmPrompt(),
                request.ignoreCacheOrDefault());
    }

    @PostMapping("/generate-dm-from-keywords")
    public InstagramDmResponse generateDmFromKeywords(@RequestBody InstagramDmKeywordsRequest request) {
        return instagramDmService.generateDmFromKeywords(
                request.keywords(),
                request.dmVersionOrDefault(),
                request.customDmPrompt());
    }

    @PostMapping("/keyword-prompt")
    public InstagramKeywordPromptResponse getKeywordPrompt(@RequestBody InstagramKeywordRequest request) {
        return instagramKeywordService.buildPromptPreview(
                request.userId(),
                request.version(),
                request.customPrompt());
    }

    @PostMapping("/extract-prices")
    public String extractPrices(@RequestBody InstagramPriceRequest request) {
        if (request == null) {
            return instagramPriceService.extractPrices("", "", null, null);
        }
        return instagramPriceService.extractPrices(
                request.text(),
                request.imageUrl(),
                request.version(),
                request.customPrompt());
    }

    @PostMapping("/price-prompt")
    public InstagramPricePromptResponse getPricePrompt(@RequestBody InstagramPricePromptRequest request) {
        if (request == null) {
            return instagramPriceService.buildPromptPreview("", null, null);
        }
        return instagramPriceService.buildPromptPreview(
                request.text(),
                request.version(),
                request.customPrompt());
    }

    @GetMapping("/keyword-versions")
    public List<String> getKeywordVersions() {
        return instagramPromptService.listVersions();
    }

    @GetMapping("/price-versions")
    public List<String> getPriceVersions() {
        return instagramPricePromptService.listVersions();
    }

    @GetMapping("/dm-versions")
    public List<String> getDmVersions() {
        return instagramDmPromptService.listVersions();
    }

    @PostMapping("/dm-prompt")
    public InstagramDmPromptResponse getDmPrompt(@RequestBody InstagramDmPromptRequest request) {
        String version = request == null ? null : request.version();
        String resolved = resolveDmPromptVersion(version);
        return new InstagramDmPromptResponse(resolved, instagramDmPromptService.loadTemplateRaw(resolved));
    }

    private String resolveDmPromptVersion(String version) {
        if (version != null && !version.isBlank()) {
            return version.trim();
        }
        List<String> versions = instagramDmPromptService.listVersions();
        if (versions.isEmpty()) {
            return "v1";
        }
        return versions.get(0);
    }
}
