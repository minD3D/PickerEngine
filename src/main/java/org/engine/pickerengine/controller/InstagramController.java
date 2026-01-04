package org.engine.pickerengine.controller;

import org.engine.pickerengine.dto.InstagramKeywordPromptResponse;
import org.engine.pickerengine.dto.InstagramKeywordRequest;
import org.engine.pickerengine.dto.InstagramKeywordResponse;
import org.engine.pickerengine.dto.InstagramProfile;
import org.engine.pickerengine.dto.InstagramProfileWithPosts;
import org.engine.pickerengine.dto.InstagramRequest;
import org.engine.pickerengine.dto.InstagramSearchResponse;
import org.engine.pickerengine.dto.InstagramSearchUser;
import org.engine.pickerengine.dto.InstagramSearchUsersPage;
import org.engine.pickerengine.service.InstagramDmPromptService;
import org.engine.pickerengine.service.InstagramDmService;
import org.engine.pickerengine.service.InstagramKeywordService;
import org.engine.pickerengine.service.InstagramService;
import org.engine.pickerengine.service.InstagramPromptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.engine.pickerengine.dto.InstagramDmPromptRequest;
import org.engine.pickerengine.dto.InstagramDmPromptResponse;
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
    private final InstagramDmPromptService instagramDmPromptService;

    public InstagramController(
            InstagramService instagramService,
            InstagramKeywordService instagramKeywordService,
            InstagramPromptService instagramPromptService,
            InstagramDmService instagramDmService,
            InstagramDmPromptService instagramDmPromptService) {
        this.instagramService = instagramService;
        this.instagramKeywordService = instagramKeywordService;
        this.instagramPromptService = instagramPromptService;
        this.instagramDmService = instagramDmService;
        this.instagramDmPromptService = instagramDmPromptService;
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

    @GetMapping("/search/keyword")
    public InstagramSearchResponse searchKeyword(@RequestParam("q") String query) {
        return instagramService.searchKeyword(query);
    }

    @GetMapping("/search/keyword/users")
    public List<InstagramSearchUser> searchKeywordUsers(@RequestParam("q") String query) {
        return instagramService.searchKeywordUsers(query);
    }

    @GetMapping("/search/keyword/users/expand")
    public List<InstagramSearchUser> searchKeywordUsersExpanded(
            @RequestParam("q") String query,
            @RequestParam(value = "maxUsers", defaultValue = "60") int maxUsers,
            @RequestParam(value = "feedCount", defaultValue = "30") int feedCount,
            @RequestParam(value = "pages", defaultValue = "1") int pages) {
        return instagramService.searchKeywordUsersExpanded(query, maxUsers, feedCount, pages);
    }

    @GetMapping("/search/keyword/users/page")
    public InstagramSearchUsersPage searchKeywordUsersPage(
            @RequestParam("q") String query,
            @RequestParam(value = "nextMaxId", required = false) String nextMaxId,
            @RequestParam(value = "searchSessionId", required = false) String searchSessionId,
            @RequestParam(value = "rankToken", required = false) String rankToken) {
        return instagramService.searchKeywordUsersPage(query, nextMaxId, searchSessionId, rankToken);
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

    @PostMapping("/keyword-prompt")
    public InstagramKeywordPromptResponse getKeywordPrompt(@RequestBody InstagramKeywordRequest request) {
        return instagramKeywordService.buildPromptPreview(
                request.userId(),
                request.version(),
                request.customPrompt());
    }

    @GetMapping("/keyword-versions")
    public List<String> getKeywordVersions() {
        return instagramPromptService.listVersions();
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
