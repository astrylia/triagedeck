-- 改成邮件链接登录，不再有密码
ALTER TABLE app_user
    DROP COLUMN password_hash;
