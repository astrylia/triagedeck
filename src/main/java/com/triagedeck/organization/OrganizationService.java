package com.triagedeck.organization;

import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.membership.Membership;
import com.triagedeck.membership.MembershipRepository;
import com.triagedeck.membership.MembershipService;
import com.triagedeck.membership.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final MembershipRepository membershipRepository;
    private final MembershipService membershipService;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            MembershipRepository membershipRepository,
            MembershipService membershipService) {
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.membershipService = membershipService;
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

    /** 当前用户加入的所有组织。 */
    @Transactional(readOnly = true)
    public List<Organization> listForUser(UUID userId) {
        List<Membership> memberships = membershipRepository.findByIdUserId(userId);
        List<UUID> orgIds = memberships.stream()
                .map(membership -> membership.getId().orgId())
                .toList();
        return organizationRepository.findAllById(orgIds);
    }

    /** 查看一个组织。不是成员时返回 404，和组织不存在一样。 */
    @Transactional(readOnly = true)
    public Organization get(UUID userId, UUID orgId) {
        membershipService.requireMembership(userId, orgId);
        return organizationRepository.findById(orgId).orElseThrow(() -> new BusinessException(ErrorCode.ORG_NOT_FOUND));
    }
}
