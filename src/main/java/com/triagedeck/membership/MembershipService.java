package com.triagedeck.membership;

import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 多租户的权限检查：每个路径里带 orgId 的接口，都要先确认当前用户是这个组织的成员。
 */
@Service
public class MembershipService {

    private final MembershipRepository membershipRepository;

    public MembershipService(MembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    /**
     * 返回当前用户在这个组织里的成员关系（里面有角色，以后按角色判断权限要用）。
     * 不是成员时抛 ORG_NOT_FOUND（404）：对外人来说，就当这个组织不存在。
     */
    @Transactional(readOnly = true)
    public Membership requireMembership(UUID userId, UUID orgId) {
        return membershipRepository
                .findById(new MembershipId(userId, orgId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORG_NOT_FOUND));
    }
}
