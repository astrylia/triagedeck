package com.triagedeck.organization;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    /** 创建组织，调用者自动成为这个组织的 OWNER。需要登录。 */
    @PostMapping("/api/orgs")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationResponse create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateOrganizationRequest request) {
        UUID currentUserId = UUID.fromString(jwt.getSubject());
        return OrganizationResponse.from(organizationService.create(currentUserId, request));
    }

    /** 当前用户加入的所有组织。 */
    @GetMapping("/api/orgs")
    public List<OrganizationResponse> listMine(@AuthenticationPrincipal Jwt jwt) {
        UUID currentUserId = UUID.fromString(jwt.getSubject());
        return organizationService.listForUser(currentUserId).stream()
                .map(OrganizationResponse::from)
                .toList();
    }

    /** 查看一个组织，只有成员能看。 */
    @GetMapping("/api/orgs/{orgId}")
    public OrganizationResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId) {
        UUID currentUserId = UUID.fromString(jwt.getSubject());
        return OrganizationResponse.from(organizationService.get(currentUserId, orgId));
    }
}
