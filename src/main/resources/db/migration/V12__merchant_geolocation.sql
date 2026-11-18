-- Optional shop point for real nearby-offer discovery. Customer coordinates are never stored.
ALTER TABLE merchant.merchants
    ADD COLUMN latitude double precision,
    ADD COLUMN longitude double precision;

ALTER TABLE merchant.merchants
    ADD CONSTRAINT ck_merchants_coordinates_pair CHECK (
        (latitude IS NULL AND longitude IS NULL) OR
        (latitude IS NOT NULL AND longitude IS NOT NULL
            AND latitude BETWEEN -90 AND 90
            AND longitude BETWEEN -180 AND 180)
    );

CREATE INDEX ix_merchants_approved_geolocation
    ON merchant.merchants (status, id)
    WHERE status = 'APPROVED' AND latitude IS NOT NULL AND longitude IS NOT NULL;
