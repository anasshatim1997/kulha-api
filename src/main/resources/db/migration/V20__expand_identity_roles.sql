ALTER TABLE identity.user_roles
    DROP CONSTRAINT ck_user_roles_role;

ALTER TABLE identity.user_roles
    ADD CONSTRAINT ck_user_roles_role CHECK (role IN (
        'CUSTOMER', 'MERCHANT', 'MERCHANT_OWNER', 'MERCHANT_STAFF',
        'SUPPORT', 'FINANCE', 'MODERATOR', 'ADMIN', 'SUPER_ADMIN'
    ));
