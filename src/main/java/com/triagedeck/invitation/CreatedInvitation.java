package com.triagedeck.invitation;

/** 创建邀请的结果：保存好的邀请，加上原始 token（数据库里没有，只能在这一刻拿到）。 */
public record CreatedInvitation(Invitation invitation, String token) {}
