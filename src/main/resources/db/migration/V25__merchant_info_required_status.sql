ALTER TABLE merchant.merchants DROP CONSTRAINT ck_merchants_status;
ALTER TABLE merchant.merchants ADD CONSTRAINT ck_merchants_status
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'INFO_REQUIRED', 'SUSPENDED'));
