-- KULHA test data refresh: exactly the 1,000 offers created by seed-1000-offers.sql.
-- Run this entire file directly in PostgreSQL after Flyway V15__offer_images.sql has been applied.
-- Pickup windows remain within the backend's existing 60-day / 24-hour publication rules.
BEGIN;

-- Keep this script independently runnable in PostgreSQL; Flyway V15 uses IF NOT EXISTS too.
ALTER TABLE offer.offers ADD COLUMN IF NOT EXISTS image_url varchar(2048);

DO $$
DECLARE
    tagged_count integer;
BEGIN
    SELECT count(*) INTO tagged_count
    FROM offer.offers
    WHERE food_notes LIKE 'KULHA_LOADTEST_1000_V1:%';

    IF tagged_count <> 1000 THEN
        RAISE EXCEPTION 'Expected exactly 1,000 KULHA load-test offers; found %. No changes applied.', tagged_count;
    END IF;
END $$;

-- Give every test offer a real food photograph and a future pickup window.
-- The image URLs are direct Unsplash CDN photo assets, grouped by offer category.
WITH numbered AS (
    SELECT o.id, o.category,
           row_number() OVER (ORDER BY o.id) AS rn
    FROM offer.offers o
    WHERE o.food_notes LIKE 'KULHA_LOADTEST_1000_V1:%'
)
UPDATE offer.offers AS o
SET image_url = CASE n.category
    WHEN 'BREAD_PASTRY' THEN (ARRAY[
        'https://images.unsplash.com/photo-1509440159596-0249088772ff?auto=format&fit=crop&w=1200&q=85',
        'https://images.unsplash.com/photo-1549931319-a545dcf3bc73?auto=format&fit=crop&w=1200&q=85',
        'https://images.unsplash.com/photo-1608198093002-ad4e005484f1?auto=format&fit=crop&w=1200&q=85'
    ])[1 + ((n.rn - 1) % 3)]
    WHEN 'PREPARED_MEALS' THEN (ARRAY[
        'https://images.unsplash.com/photo-1547592180-85f173990554?auto=format&fit=crop&w=1200&q=85',
        'https://images.unsplash.com/photo-1546069901-ba9599a7e63c?auto=format&fit=crop&w=1200&q=85',
        'https://images.unsplash.com/photo-1547592166-23ac45744acd?auto=format&fit=crop&w=1200&q=85'
    ])[1 + ((n.rn - 1) % 3)]
    WHEN 'GROCERIES' THEN (ARRAY[
        'https://images.unsplash.com/photo-1542838132-92c53300491e?auto=format&fit=crop&w=1200&q=85',
        'https://images.unsplash.com/photo-1540420773420-3366772f4999?auto=format&fit=crop&w=1200&q=85',
        'https://images.unsplash.com/photo-1543168256-418811576931?auto=format&fit=crop&w=1200&q=85'
    ])[1 + ((n.rn - 1) % 3)]
    WHEN 'FRUIT_VEG' THEN (ARRAY[
        'https://images.unsplash.com/photo-1610832958506-aa56368176cf?auto=format&fit=crop&w=1200&q=85',
        'https://images.unsplash.com/photo-1490474418585-ba9bad8fd0ea?auto=format&fit=crop&w=1200&q=85',
        'https://images.unsplash.com/photo-1568702846914-96b305d2aaeb?auto=format&fit=crop&w=1200&q=85'
    ])[1 + ((n.rn - 1) % 3)]
    WHEN 'DRINKS' THEN (ARRAY[
        'https://images.unsplash.com/photo-1544145945-f90425340c7e?auto=format&fit=crop&w=1200&q=85',
        'https://images.unsplash.com/photo-1509042239860-f550ce710b93?auto=format&fit=crop&w=1200&q=85'
    ])[1 + ((n.rn - 1) % 2)]
    WHEN 'SURPRISE_BASKET' THEN (ARRAY[
        'https://images.unsplash.com/photo-1490645935967-10de6ba17061?auto=format&fit=crop&w=1200&q=85',
        'https://images.unsplash.com/photo-1512621776951-a57141f2eefd?auto=format&fit=crop&w=1200&q=85',
        'https://images.unsplash.com/photo-1547592180-85f173990554?auto=format&fit=crop&w=1200&q=85'
    ])[1 + ((n.rn - 1) % 3)]
    ELSE 'https://images.unsplash.com/photo-1546549032-9571cd6b27df?auto=format&fit=crop&w=1200&q=85'
END,
    pickup_start = CURRENT_TIMESTAMP + INTERVAL '2 hours'
                   + (((n.rn - 1) % 60) * INTERVAL '1 day'),
    pickup_end = CURRENT_TIMESTAMP + INTERVAL '5 hours'
                 + (((n.rn - 1) % 60) * INTERVAL '1 day'),
    status = 'PUBLISHED',
    published_at = COALESCE(o.published_at, CURRENT_TIMESTAMP),
    reservation_started_at = CASE
        WHEN o.quantity_reserved > 0 THEN COALESCE(o.reservation_started_at, CURRENT_TIMESTAMP)
        ELSE o.reservation_started_at
    END,
    version = o.version + 1,
    updated_at = CURRENT_TIMESTAMP
FROM numbered n
WHERE o.id = n.id;

-- Replace merchant locations only for merchants linked to these 1,000 test offers.
-- Coordinates intentionally represent the relevant Moroccan city centre, not a made-up
-- street-level GPS pin. The address itself is a Moroccan sample address for UI/map testing.
WITH locations(location_no, address, city, district, latitude, longitude) AS (
    VALUES
    ( 1, '14 rue du Marché', 'Casablanca', 'Maarif', 33.5899, -7.6039),
    ( 2, '22 boulevard Zerktouni', 'Casablanca', 'Gauthier', 33.5899, -7.6039),
    ( 3, '8 rue Moussa Ibnou Noussair', 'Casablanca', 'Bourgogne', 33.5899, -7.6039),
    ( 4, '51 avenue 2 Mars', 'Casablanca', 'Derb Sultan', 33.5899, -7.6039),
    ( 5, '17 rue de l’Oasis', 'Casablanca', 'Oasis', 33.5899, -7.6039),
    ( 6, '12 rue des Consuls', 'Rabat', 'Médina', 34.0209, -6.8416),
    ( 7, '44 avenue de France', 'Rabat', 'Agdal', 34.0209, -6.8416),
    ( 8, '19 avenue Mohammed V', 'Rabat', 'Hassan', 34.0209, -6.8416),
    ( 9, '7 avenue Annakhil', 'Rabat', 'Hay Riad', 34.0209, -6.8416),
    (10, '31 avenue Mohammed V', 'Marrakech', 'Guéliz', 31.6295, -7.9811),
    (11, '10 derb Dabachi', 'Marrakech', 'Médina', 31.6295, -7.9811),
    (12, '4 rue Ibn Aïcha', 'Marrakech', 'Hivernage', 31.6295, -7.9811),
    (13, '2 boulevard Abdelkrim Khattabi', 'Marrakech', 'Semlalia', 31.6295, -7.9811),
    (14, '16 boulevard Pasteur', 'Tanger', 'Centre Ville', 35.7595, -5.8340),
    (15, '8 avenue Mohammed VI', 'Tanger', 'Malabata', 35.7595, -5.8340),
    (16, '3 rue de Belgique', 'Tanger', 'Iberia', 35.7595, -5.8340),
    (17, '28 avenue Hassan II', 'Fès', 'Ville Nouvelle', 34.0331, -5.0003),
    (18, '7 derb Sidi Bou Jida', 'Fès', 'Médina', 34.0331, -5.0003),
    (19, '2 rue Al Qods', 'Fès', 'Narjiss', 34.0331, -5.0003),
    (20, '10 avenue Hassan II', 'Agadir', 'Talborjt', 30.4278, -9.5981),
    (21, '4 boulevard du 20 Août', 'Agadir', 'Centre Ville', 30.4278, -9.5981),
    (22, '18 avenue des FAR', 'Agadir', 'Dakhla', 30.4278, -9.5981),
    (23, '21 avenue des FAR', 'Meknès', 'Hamria', 33.8935, -5.5473),
    (24, '9 place El Hedim', 'Meknès', 'Médina', 33.8935, -5.5473),
    (25, '13 avenue Mohammed V', 'Kénitra', 'Centre Ville', 34.2610, -6.5802),
    (26, '3 avenue Bir Rami', 'Kénitra', 'Bir Rami', 34.2610, -6.5802),
    (27, '8 boulevard Mohammed Derfoufi', 'Oujda', 'Centre Ville', 34.6814, -1.9086),
    (28, '14 rue Marrakech', 'Oujda', 'Lazaret', 34.6814, -1.9086),
    (29, '6 avenue Hassan II', 'Tétouan', 'Centre Ville', 35.5889, -5.3626),
    (30, '17 avenue des FAR', 'Tétouan', 'Ensanche', 35.5889, -5.3626),
    (31, '2 avenue Mohammed V', 'Béni Mellal', 'Centre Ville', 32.3373, -6.3498),
    (32, '5 avenue Mohammed VI', 'Essaouira', 'Médina', 31.5085, -9.7595),
    (33, '11 avenue Hassan II', 'El Jadida', 'Centre Ville', 33.2316, -8.5007),
    (34, '6 boulevard Mohammed V', 'Safi', 'Centre Ville', 32.2994, -9.2372),
    (35, '9 avenue des FAR', 'Nador', 'Centre Ville', 35.1681, -2.9335),
    (36, '3 avenue Hassan II', 'Ifrane', 'Centre Ville', 33.5228, -5.1109),
    (37, '15 avenue Mohammed V', 'Casablanca', 'Aïn Sebaâ', 33.5899, -7.6039),
    (38, '5 rue Abou Inane', 'Rabat', 'L’Océan', 34.0209, -6.8416),
    (39, '18 boulevard Allal Al Fassi', 'Marrakech', 'Daoudiate', 31.6295, -7.9811),
    (40, '7 rue de la Liberté', 'Tanger', 'Centre Ville', 35.7595, -5.8340)
), merchant_rows AS (
    SELECT DISTINCT m.id,
           row_number() OVER (ORDER BY m.id) AS rn
    FROM merchant.merchants m
    JOIN offer.offers o ON o.merchant_id = m.id
    WHERE o.food_notes LIKE 'KULHA_LOADTEST_1000_V1:%'
), assigned AS (
    SELECT mr.id, l.address, l.city, l.district, l.latitude, l.longitude
    FROM merchant_rows mr
    JOIN locations l ON l.location_no = 1 + ((mr.rn - 1) % 40)
)
UPDATE merchant.merchants m
SET address = a.address,
    city = a.city,
    district = a.district,
    latitude = a.latitude,
    longitude = a.longitude,
    updated_at = CURRENT_TIMESTAMP
FROM assigned a
WHERE m.id = a.id;

-- Guard the business rules before committing.
DO $$
DECLARE
    invalid_count integer;
    image_count integer;
    merchant_count integer;
BEGIN
    SELECT count(*) INTO invalid_count
    FROM offer.offers o
    WHERE o.food_notes LIKE 'KULHA_LOADTEST_1000_V1:%'
      AND (
          o.status <> 'PUBLISHED'
          OR o.image_url IS NULL
          OR o.image_url NOT LIKE 'https://images.unsplash.com/%'
          OR o.pickup_start <= CURRENT_TIMESTAMP
          OR o.pickup_end <= o.pickup_start
          OR o.pickup_end > CURRENT_TIMESTAMP + INTERVAL '60 days'
          OR o.pickup_end - o.pickup_start > INTERVAL '24 hours'
      );

    SELECT count(*) INTO image_count
    FROM offer.offers
    WHERE food_notes LIKE 'KULHA_LOADTEST_1000_V1:%' AND image_url IS NOT NULL;

    SELECT count(DISTINCT m.id) INTO merchant_count
    FROM merchant.merchants m
    JOIN offer.offers o ON o.merchant_id = m.id
    WHERE o.food_notes LIKE 'KULHA_LOADTEST_1000_V1:%'
      AND m.city IN ('Casablanca','Rabat','Marrakech','Tanger','Fès','Agadir','Meknès','Kénitra',
                     'Oujda','Tétouan','Béni Mellal','Essaouira','El Jadida','Safi','Nador','Ifrane')
      AND m.address IS NOT NULL
      AND m.latitude IS NOT NULL
      AND m.longitude IS NOT NULL;

    IF invalid_count <> 0 OR image_count <> 1000 OR merchant_count = 0 THEN
        RAISE EXCEPTION 'Test data validation failed: invalid offers %, images %, Moroccan merchants %.',
            invalid_count, image_count, merchant_count;
    END IF;
END $$;

COMMIT;

-- Final report: expect 1,000 published offers, 1,000 images, future pickup windows,
-- and Moroccan merchant cities only.
SELECT o.status,
       count(*) AS offers,
       count(o.image_url) AS offers_with_images,
       min(o.pickup_start) AS first_pickup,
       max(o.pickup_end) AS last_pickup,
       count(*) FILTER (WHERE o.pickup_end > CURRENT_TIMESTAMP
                         AND o.pickup_end <= CURRENT_TIMESTAMP + INTERVAL '60 days') AS valid_pickup_windows
FROM offer.offers o
WHERE o.food_notes LIKE 'KULHA_LOADTEST_1000_V1:%'
GROUP BY o.status;

SELECT m.city, count(DISTINCT o.id) AS offers
FROM offer.offers o
JOIN merchant.merchants m ON m.id = o.merchant_id
WHERE o.food_notes LIKE 'KULHA_LOADTEST_1000_V1:%'
GROUP BY m.city
ORDER BY m.city;
