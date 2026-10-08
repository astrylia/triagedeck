package com.triagedeck.auth.password;

/**
 * 需要做常见密码检查的请求实现这个接口，提供密码和用来对比的邮箱、名字。
 * 这样 password 包不用依赖具体的请求类，以后改密码、重置密码的请求也能直接标 @NotCommonPassword。
 */
public interface PasswordCandidate {

    String password();

    String email();

    String name();
}
