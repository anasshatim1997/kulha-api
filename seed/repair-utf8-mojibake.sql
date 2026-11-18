-- One-time repair for strings that were UTF-8 seed bytes interpreted by a
-- Windows-1252 psql client before insertion (examples: pÃ¢tisserie, lâ€™Oasis).
-- Run only after reviewing the preview query in README-UTF8-REPAIR.md.
-- Rebuild the configured Elasticsearch offer index after this transaction.
SET client_encoding = 'UTF8';

BEGIN;

DO $$
BEGIN
    IF current_setting('kulha.allow_encoding_repair', true) IS DISTINCT FROM 'true' THEN
        RAISE EXCEPTION 'Refusing text repair. Set PGOPTIONS=-c kulha.allow_encoding_repair=true for this psql session.';
    END IF;
    IF current_database() !~* '(^|[_-])(dev|test|local)([_-]|$)' AND current_database() !~* '^kulha$' THEN
        RAISE EXCEPTION 'Refusing text repair in database %. Use a development/test/local database.', current_database();
    END IF;
    IF current_setting('server_encoding') <> 'UTF8' OR current_setting('client_encoding') <> 'UTF8' THEN
        RAISE EXCEPTION 'UTF-8 repair requires both server_encoding and client_encoding to be UTF8 (server %, client %).',
            current_setting('server_encoding'), current_setting('client_encoding');
    END IF;
END $$;

CREATE OR REPLACE FUNCTION pg_temp.kulha_repair_utf8_mojibake(value text)
RETURNS text
LANGUAGE plpgsql
AS $$
BEGIN
    IF value IS NULL OR (position('Ã' IN value) = 0 AND position('â€' IN value) = 0) THEN
        RETURN value;
    END IF;

    -- Reinterpret the Windows-1252 bytes as UTF-8. This repairs the original
    -- bytes instead of changing presentation strings in the frontend.
    RETURN convert_from(convert_to(value, 'WIN1252'), 'UTF8');
EXCEPTION
    WHEN SQLSTATE '22P05' OR SQLSTATE '22021' THEN
        -- Leave values containing other non-Windows-1252 characters untouched.
        RETURN value;
END;
$$;

-- Restrict updates to rows containing the recognizable mojibake signatures.
UPDATE merchant.merchants
SET name = pg_temp.kulha_repair_utf8_mojibake(name),
    address = pg_temp.kulha_repair_utf8_mojibake(address),
    city = pg_temp.kulha_repair_utf8_mojibake(city),
    district = pg_temp.kulha_repair_utf8_mojibake(district),
    opening_hours = pg_temp.kulha_repair_utf8_mojibake(opening_hours),
    pickup_instructions = pg_temp.kulha_repair_utf8_mojibake(pickup_instructions),
    decision_reason = pg_temp.kulha_repair_utf8_mojibake(decision_reason),
    updated_at = now()
WHERE name LIKE '%Ã%' OR name LIKE '%â€%'
   OR address LIKE '%Ã%' OR address LIKE '%â€%'
   OR city LIKE '%Ã%' OR city LIKE '%â€%'
   OR district LIKE '%Ã%' OR district LIKE '%â€%'
   OR opening_hours LIKE '%Ã%' OR opening_hours LIKE '%â€%'
   OR pickup_instructions LIKE '%Ã%' OR pickup_instructions LIKE '%â€%'
   OR decision_reason LIKE '%Ã%' OR decision_reason LIKE '%â€%';

UPDATE offer.offers
SET title = pg_temp.kulha_repair_utf8_mojibake(title),
    description = pg_temp.kulha_repair_utf8_mojibake(description),
    food_notes = pg_temp.kulha_repair_utf8_mojibake(food_notes),
    updated_at = now()
WHERE title LIKE '%Ã%' OR title LIKE '%â€%'
   OR description LIKE '%Ã%' OR description LIKE '%â€%'
   OR food_notes LIKE '%Ã%' OR food_notes LIKE '%â€%';

COMMIT;
