package com.triagedeck.organization;

import java.util.UUID;

public record OrganizationResponse(UUID id, String name, String slug) {

    public static OrganizationResponse from(Organization organization) {
        return new OrganizationResponse(organization.getId(), organization.getName(), organization.getSlug());
    }
}
