package com.triagedeck.organization;

import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.membership.Membership;
import com.triagedeck.membership.MembershipRepository;
import com.triagedeck.membership.Role;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final MembershipRepository membershipRepository;

    public OrganizationService(
            OrganizationRepository organizationRepository, MembershipRepository membershipRepository) {
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
    }

    /**
     * 创建组织，并让创建者成为这个组织的 OWNER，返回保存后的 Organization。
     * slug 已被占用时抛 ORG_SLUG_ALREADY_USED（409）。
     */
    @Transactional
    public Organization create(UUID ownerId, CreateOrganizationRequest request) {
        String name = request.name();
        String slug = request.slug();
        if (organizationRepository.existsBySlug(slug)) {
            throw new BusinessException(ErrorCode.ORG_SLUG_ALREADY_USED);
        }
        Organization organization = new Organization(name, slug);
        Organization saved;
        try {
            saved = organizationRepository.saveAndFlush(organization);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ORG_SLUG_ALREADY_USED);
        }
        Membership membership = new Membership(ownerId, saved.getId(), Role.OWNER);
        membershipRepository.save(membership);
        return saved;
    }
}
