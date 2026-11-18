BEGIN;
SELECT pg_advisory_xact_lock(hashtext('kulha-map-demo-v1'));
DO $$
BEGIN
    IF current_setting('kulha.allow_map_demo_seed', true) IS DISTINCT FROM 'true' THEN
        RAISE EXCEPTION 'Refusing map demo seed. Set PGOPTIONS=-c kulha.allow_map_demo_seed=true for this psql session.';
    END IF;
    IF current_database() !~* '(^|[_-])(dev|test|local)([_-]|$)' AND current_database() !~* '^kulha$' THEN
        RAISE EXCEPTION 'Refusing map demo seed in database %. Use a development/test/local database.', current_database();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='merchant' AND table_name='merchants' AND column_name='latitude')
       OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='merchant' AND table_name='merchants' AND column_name='longitude')
       OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='merchant' AND table_name='merchants' AND column_name='district')
       OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='merchant' AND table_name='merchants' AND column_name='pickup_instructions')
       OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='offer' AND table_name='offers' AND column_name='reservation_started_at') THEN
        RAISE EXCEPTION 'Required merchant geolocation or offer lifecycle migration is missing. Start the backend once to apply Flyway migrations.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN unnest(c.conkey) AS k(attnum) ON true
        JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.attnum
        WHERE c.conrelid='merchant.merchants'::regclass AND c.contype='f' AND a.attname='owner_user_id'
    ) THEN
        RAISE EXCEPTION 'merchant.owner_user_id now has a foreign key. This isolated demo seed must be updated before use.';
    END IF;
END $$;

CREATE TEMP TABLE _kulha_map_demo_merchants ON COMMIT DROP AS
SELECT merchant_no,
       md5('kulha-map-demo-v1:merchant:' || merchant_no::text)::uuid AS id,
       md5('kulha-map-demo-v1:owner:' || merchant_no::text)::uuid AS owner_user_id,
       name, business_type, address, city, district, phone, opening_hours, pickup_instructions, latitude, longitude
FROM (VALUES
    (1, 'Boulangerie Nour [KULHA MAP DEMO]', 'BAKERY', '14 rue du Marché', 'Casablanca', 'Maarif', '+212600820001', 'Lun-Sam 07:00-20:00', 'Retrait au comptoir, entrée rue du Marché', 33.5885, -7.6348),
    (2, 'Pâtisserie Al Baraka [KULHA MAP DEMO]', 'BAKERY', '22 boulevard Zerktouni', 'Casablanca', 'Gauthier', '+212600820002', 'Tous les jours 08:00-21:00', 'Présenter la réservation à la caisse', 33.5919, -7.626),
    (3, 'Café Bahia [KULHA MAP DEMO]', 'CAFE', '8 rue Moussa Ibnou Noussair', 'Casablanca', 'Bourgogne', '+212600820003', 'Lun-Dim 07:30-22:00', 'Retrait près du comptoir café', 33.596, -7.6468),
    (4, 'Maison du Tajine [KULHA MAP DEMO]', 'RESTAURANT', '51 avenue 2 Mars', 'Casablanca', 'Racine', '+212600820004', 'Lun-Sam 11:00-23:00', 'Entrée principale, demander le sac anti-gaspi', 33.5904, -7.6408),
    (5, 'Marché Californie [KULHA MAP DEMO]', 'SUPERMARKET', '3 rue de la Forêt', 'Casablanca', 'Californie', '+212600820005', 'Tous les jours 08:00-22:00', 'Point retrait près de l’accueil', 33.5521, -7.645),
    (6, 'Le Fournil de l’Oasis [KULHA MAP DEMO]', 'BAKERY', '17 rue de l’Oasis', 'Casablanca', 'Oasis', '+212600820006', 'Lun-Sam 06:30-19:30', 'Retrait à la boulangerie', 33.5655, -7.624),
    (7, 'Snack Casa Medina [KULHA MAP DEMO]', 'RESTAURANT', '9 rue de Fès', 'Casablanca', 'Habous', '+212600820007', 'Lun-Dim 10:00-22:00', 'Retrait au comptoir des commandes', 33.573, -7.6107),
    (8, 'Épicerie Anfa [KULHA MAP DEMO]', 'OTHER', '6 avenue du Phare', 'Casablanca', 'Aïn Diab', '+212600820008', 'Tous les jours 09:00-21:00', 'Sonner à l’entrée du magasin', 33.5905, -7.6655)
) AS v(merchant_no, name, business_type, address, city, district, phone, opening_hours, pickup_instructions, latitude, longitude)
WHERE merchant_no > 0;

INSERT INTO merchant.merchants AS existing (
    id, owner_user_id, name, business_type, address, city, district, phone,
    opening_hours, pickup_instructions, latitude, longitude, status,
    decided_at, created_at, updated_at
)
SELECT id, owner_user_id, name, business_type, address, city, district, phone,
       opening_hours, pickup_instructions, latitude, longitude, 'APPROVED',
       now(), now(), now()
FROM _kulha_map_demo_merchants
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    business_type = EXCLUDED.business_type,
    address = EXCLUDED.address,
    city = EXCLUDED.city,
    district = EXCLUDED.district,
    phone = EXCLUDED.phone,
    opening_hours = EXCLUDED.opening_hours,
    pickup_instructions = EXCLUDED.pickup_instructions,
    latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    status = 'APPROVED',
    decided_at = now(),
    updated_at = now()
WHERE existing.name LIKE '%[KULHA MAP DEMO]%';

WITH demo_offers AS (
    SELECT * FROM (VALUES
        (1, 1, 'Panier viennoiseries du matin', 'Un assortiment de croissants et pains au chocolat préparés ce matin.', 'BREAD_PASTRY', 2200, 4200, 8, 1),
        (2, 1, 'Pain & pâtisserie du jour', 'Un panier généreux de pains et petites pâtisseries.', 'BREAD_PASTRY', 2800, 5000, 6, 2),
        (3, 2, 'Assortiment de pâtisseries fines', 'Sélection de pâtisseries du jour à partager.', 'BREAD_PASTRY', 3500, 6500, 5, 1),
        (4, 2, 'Mini-cakes et biscuits', 'Petits gâteaux et biscuits de la vitrine.', 'BREAD_PASTRY', 1800, 3200, 9, 2),
        (5, 3, 'Petit-déjeuner du café', 'Viennoiseries, jus frais et douceur du jour.', 'SURPRISE_BASKET', 3000, 5500, 5, 1),
        (6, 3, 'Fruits frais à partager', 'Sélection de fruits frais du comptoir.', 'FRUIT_VEG', 2500, 4000, 7, 2),
        (7, 4, 'Tajine du jour', 'Portion familiale préparée avec des produits frais.', 'PREPARED_MEALS', 4800, 7500, 4, 2),
        (8, 4, 'Couscous maison', 'Couscous du jour avec légumes de saison.', 'PREPARED_MEALS', 5200, 8500, 3, 3),
        (9, 5, 'Panier épicerie essentiel', 'Produits secs et essentiels de l’épicerie.', 'GROCERIES', 3900, 6200, 6, 1),
        (10, 5, 'Fruits et légumes du marché', 'Sélection de saison prête à récupérer.', 'FRUIT_VEG', 2600, 4400, 10, 2),
        (11, 6, 'Pain complet & tradition', 'Pains du jour préparés au levain.', 'BREAD_PASTRY', 1600, 2900, 8, 1),
        (12, 6, 'Paniers anti-gaspi du fournil', 'Assortiment surprise de fin de journée.', 'SURPRISE_BASKET', 2400, 4800, 7, 3),
        (13, 7, 'Pastilla individuelle', 'Pastilla salée préparée dans la journée.', 'PREPARED_MEALS', 3300, 5200, 5, 2),
        (14, 7, 'Briouates assorties', 'Assortiment de briouates maison.', 'PREPARED_MEALS', 2900, 4700, 6, 3),
        (15, 8, 'Panier fraîcheur Anfa', 'Fruits, légumes et produits frais.', 'FRUIT_VEG', 4500, 7200, 4, 1),
        (16, 8, 'Boissons & encas', 'Sélection de boissons et petits encas.', 'DRINKS', 2100, 3600, 8, 2)
    ) AS v(offer_no, merchant_no, title, description, category, price_minor, original_price_minor, quantity_total, pickup_offset)
    WHERE offer_no > 0
)
INSERT INTO offer.offers AS existing (
    id, merchant_id, title, description, category, price_minor, original_price_minor,
    quantity_total, quantity_reserved, pickup_start, pickup_end,
    allergens_status, allergens, food_notes, status, published_at, version, created_at, updated_at
)
SELECT md5('kulha-map-demo-v1:offer:' || d.offer_no::text)::uuid,
       m.id,
       d.title,
       d.description,
       d.category,
       d.price_minor,
       d.original_price_minor,
       d.quantity_total,
       0,
       now() + (d.pickup_offset * interval '1 hour'),
       now() + (d.pickup_offset * interval '1 hour') + interval '3 hours',
       'UNKNOWN',
       ARRAY[]::varchar(20)[],
       NULL,
       'PUBLISHED',
       now() - interval '15 minutes',
       1,
       now(),
       now()
FROM demo_offers d
JOIN _kulha_map_demo_merchants m ON m.merchant_no=d.merchant_no
ON CONFLICT (id) DO UPDATE SET
    merchant_id = EXCLUDED.merchant_id,
    title = EXCLUDED.title,
    description = EXCLUDED.description,
    category = EXCLUDED.category,
    price_minor = EXCLUDED.price_minor,
    original_price_minor = EXCLUDED.original_price_minor,
    quantity_total = EXCLUDED.quantity_total,
    quantity_reserved = 0,
    reservation_started_at = NULL,
    pickup_start = EXCLUDED.pickup_start,
    pickup_end = EXCLUDED.pickup_end,
    allergens_status = 'UNKNOWN',
    allergens = ARRAY[]::varchar(20)[],
    food_notes = NULL,
    status = 'PUBLISHED',
    published_at = EXCLUDED.published_at,
    version = existing.version + 1,
    updated_at = now()
WHERE existing.merchant_id IN (SELECT id FROM _kulha_map_demo_merchants);

DO $$
DECLARE
    merchant_count integer;
    offer_count integer;
BEGIN
    SELECT count(*) INTO merchant_count
    FROM merchant.merchants
    WHERE name LIKE '%[KULHA MAP DEMO]%'
      AND city='Casablanca'
      AND latitude IS NOT NULL AND longitude IS NOT NULL;
    SELECT count(*) INTO offer_count
    FROM offer.offers o
    WHERE o.id IN (SELECT md5('kulha-map-demo-v1:offer:' || n::text)::uuid FROM generate_series(1,16) n)
      AND o.status='PUBLISHED'
      AND o.pickup_end > now();
    IF merchant_count <> 8 OR offer_count <> 16 THEN
        RAISE EXCEPTION 'Map demo seed incomplete: found % demo merchants and % active demo offers (expected 8 and 16).', merchant_count, offer_count;
    END IF;
END $$;

COMMIT;
