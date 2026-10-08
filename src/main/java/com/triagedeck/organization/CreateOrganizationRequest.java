package com.triagedeck.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建组织的请求。slug 是组织在网址里的短名字（比如 acme-support），全局唯一，由用户自己填。
 * 只允许小写字母、数字和单个连字符，不能以连字符开头或结尾，这样放进网址里不需要转义。
 */
public record CreateOrganizationRequest(
        @NotBlank @Size(max = 100) String name,

        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(
                regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                message = "must contain only lowercase letters, digits and single hyphens, e.g. acme-support")
        String slug) {}
