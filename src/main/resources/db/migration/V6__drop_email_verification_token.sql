-- 注册改成"先收验证码、再建账号"：验证码存在 Redis 里，数据库里的每个账号建出来时邮箱就已经验证过。
-- 原来的验证链接表和"邮箱何时验证"这一列都用不到了。
DROP TABLE email_verification_token;

ALTER TABLE app_user DROP COLUMN email_verified_at;
