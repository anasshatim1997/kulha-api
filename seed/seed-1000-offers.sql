SET client_encoding = 'UTF8';

-- KULHA! deterministic, repeatable 1,000-offer load-test seed.
-- PostgreSQL 16 (the project's compose.yaml version).
-- This script writes only its own tagged test merchants and offers.
-- It never updates/deletes pre-existing merchant or offer records.

BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
SELECT pg_advisory_xact_lock(hashtext('kulha:seed:1000-offers:v1'));

DO $$
DECLARE
    required_column_count integer;
    actual_column_count integer;
    existing_seed_count integer;
BEGIN
    IF current_database() !~* '(^|[_-])(dev|test|local|load|loadtest)([_-]|$)'
       AND current_setting('kulha.allow_load_seed', true) IS DISTINCT FROM 'true' THEN
        RAISE EXCEPTION 'Refusing to seed database "%". Use a dev/test/local/load/loadtest database name, or explicitly set PGOPTIONS="-c kulha.allow_load_seed=true" for this session.', current_database();
    END IF;

    IF to_regclass('merchant.merchants') IS NULL OR to_regclass('offer.offers') IS NULL THEN
        RAISE EXCEPTION 'Required tables merchant.merchants and offer.offers are missing. Apply Flyway migrations first.';
    END IF;

    SELECT count(*) INTO required_column_count
    FROM (VALUES
        ('merchant','merchants','id'), ('merchant','merchants','owner_user_id'),
        ('merchant','merchants','name'), ('merchant','merchants','business_type'),
        ('merchant','merchants','address'), ('merchant','merchants','city'),
        ('merchant','merchants','district'), ('merchant','merchants','phone'),
        ('merchant','merchants','opening_hours'), ('merchant','merchants','pickup_instructions'),
        ('merchant','merchants','status'), ('merchant','merchants','decided_by'), ('merchant','merchants','decided_at'),
        ('merchant','merchants','created_at'), ('merchant','merchants','updated_at'),
        ('offer','offers','id'), ('offer','offers','merchant_id'),
        ('offer','offers','title'), ('offer','offers','description'), ('offer','offers','category'),
        ('offer','offers','price_minor'), ('offer','offers','original_price_minor'),
        ('offer','offers','quantity_total'), ('offer','offers','quantity_reserved'),
        ('offer','offers','reservation_started_at'), ('offer','offers','pickup_start'),
        ('offer','offers','pickup_end'), ('offer','offers','allergens_status'),
        ('offer','offers','allergens'), ('offer','offers','food_notes'), ('offer','offers','status'),
        ('offer','offers','published_at'), ('offer','offers','version'),
        ('offer','offers','created_at'), ('offer','offers','updated_at')
    ) AS required(table_schema, table_name, column_name)
    JOIN information_schema.columns c
      ON c.table_schema = required.table_schema
     AND c.table_name = required.table_name
     AND c.column_name = required.column_name;

    SELECT 35 INTO actual_column_count;
    IF required_column_count <> actual_column_count THEN
        RAISE EXCEPTION 'Schema precondition failed: expected % required columns, found %. Run seed/preflight.sql and compare to Flyway migrations.',
            actual_column_count, required_column_count;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'offer.offers'::regclass AND conname = 'ck_offers_status'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'offer.offers'::regclass AND conname = 'ck_offers_category'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'offer.offers'::regclass AND conname = 'ck_offers_qty'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'offer.offers'::regclass AND conname = 'ck_offers_price'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'offer.offers'::regclass AND conname = 'ck_offers_window'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'offer.offers'::regclass AND conname = 'ck_offers_allergens'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'offer.offers'::regclass AND conname = 'ck_offer_reservation_started'
    ) THEN
        RAISE EXCEPTION 'Offer CHECK constraints do not match the inspected V7/V8 schema. Refusing to seed.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'merchant.merchants'::regclass AND conname = 'ck_merchants_status'
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'merchant.merchants'::regclass AND conname = 'ck_merchants_type'
    ) THEN
        RAISE EXCEPTION 'Merchant CHECK constraints do not match the inspected V3 schema. Refusing to seed.';
    END IF;

    -- V3 explicitly documents that owner_user_id has no cross-schema FK.
    -- If a future migration adds one, this seed must be revisited rather than
    -- manufacturing orphaned owner references.
    IF EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN unnest(c.conkey) AS key(attnum) ON true
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = key.attnum
        WHERE c.conrelid = 'merchant.merchants'::regclass
          AND c.contype = 'f'
          AND a.attname = 'owner_user_id'
    ) THEN
        RAISE EXCEPTION 'merchant.owner_user_id now has a foreign key. Add valid test identity users before using this seed.';
    END IF;

    SELECT count(*) INTO existing_seed_count
    FROM offer.offers
    WHERE left(COALESCE(food_notes, ''), length('KULHA_LOADTEST_1000_V1:')) = 'KULHA_LOADTEST_1000_V1:';

    IF existing_seed_count > 1000 THEN
        RAISE EXCEPTION 'More than 1,000 records already carry this seed marker (found %). Inspect before continuing.', existing_seed_count;
    END IF;
END $$;

-- The schema intentionally has no category table. Category values are constrained strings.
-- Merchant.owner_user_id has no FK to identity.users in V3, so isolated synthetic merchants
-- are allowed by the inspected schema and cannot log in as real merchant accounts.
CREATE TEMP TABLE _kulha_seed_merchants ON COMMIT DROP AS
SELECT t.merchant_no,
       format('%s-%s-%s-%s-%s',
           substr(id_hash,1,8), substr(id_hash,9,4), substr(id_hash,13,4),
           substr(id_hash,17,4), substr(id_hash,21,12))::uuid AS id,
       format('%s-%s-%s-%s-%s',
           substr(owner_hash,1,8), substr(owner_hash,9,4), substr(owner_hash,13,4),
           substr(owner_hash,17,4), substr(owner_hash,21,12))::uuid AS owner_user_id,
       t.name,
       t.business_type,
       t.address,
       t.city,
       t.district,
       t.phone,
       t.opening_hours,
       t.pickup_instructions
FROM (
    SELECT merchant_no, name, business_type, address, city, district, phone, opening_hours, pickup_instructions,
           md5('kulha-loadtest-v1:merchant:' || merchant_no::text) AS id_hash,
           md5('kulha-loadtest-v1:owner:' || merchant_no::text) AS owner_hash
    FROM (VALUES
        ( 1,'Boulangerie Nour [KULHA-LOADTEST]','BAKERY','14 rue du Marché','Casablanca','Maarif','+212600100001','Lun-Sam 07:00-20:00','Retrait au comptoir, entrée rue du Marché'),
        ( 2,'Pâtisserie Al Baraka [KULHA-LOADTEST]','BAKERY','22 boulevard Zerktouni','Casablanca','Gauthier','+212600100002','Tous les jours 08:00-21:00','Présenter la réservation à la caisse'),
        ( 3,'Café Bahia [KULHA-LOADTEST]','CAFE','8 rue Moussa Ibnou Noussair','Casablanca','Bourgogne','+212600100003','Lun-Dim 07:30-22:00','Retrait près du comptoir café'),
        ( 4,'Maison du Tajine [KULHA-LOADTEST]','RESTAURANT','51 avenue 2 Mars','Casablanca','Derb Sultan','+212600100004','Lun-Sam 11:00-23:00','Entrée principale, demander le sac anti-gaspi'),
        ( 5,'Marché Californie [KULHA-LOADTEST]','SUPERMARKET','3 rue de la Forêt','Casablanca','Californie','+212600100005','Tous les jours 08:00-22:00','Point retrait près de l’accueil'),
        ( 6,'Le Fournil de l’Oasis [KULHA-LOADTEST]','BAKERY','17 rue de l’Oasis','Casablanca','Oasis','+212600100006','Lun-Sam 06:30-19:30','Retrait à la boulangerie'),
        ( 7,'Snack Casa Medina [KULHA-LOADTEST]','RESTAURANT','9 rue de Fès','Casablanca','Habous','+212600100007','Lun-Dim 10:00-22:00','Retrait au comptoir des commandes'),
        ( 8,'Épicerie Anfa [KULHA-LOADTEST]','OTHER','6 avenue du Phare','Casablanca','Ain Diab','+212600100008','Tous les jours 09:00-21:00','Sonner à l’entrée du magasin'),
        ( 9,'Boulangerie Les Oudayas [KULHA-LOADTEST]','BAKERY','12 rue des Consuls','Rabat','Médina','+212600100009','Lun-Sam 07:00-20:00','Retrait à côté du fournil'),
        (10,'Café Agdal [KULHA-LOADTEST]','CAFE','44 avenue de France','Rabat','Agdal','+212600100010','Tous les jours 08:00-22:00','Présenter le code de réservation'),
        (11,'Table de Hassan [KULHA-LOADTEST]','RESTAURANT','19 avenue Mohammed V','Rabat','Hassan','+212600100011','Lun-Dim 11:30-23:00','Retrait à l’accueil du restaurant'),
        (12,'Market Hay Riad [KULHA-LOADTEST]','SUPERMARKET','7 avenue Annakhil','Rabat','Hay Riad','+212600100012','Tous les jours 08:00-22:00','Zone de retrait à l’entrée'),
        (13,'Pâtisserie Chellah [KULHA-LOADTEST]','BAKERY','5 rue Jbel Moussa','Rabat','L’Océan','+212600100013','Lun-Sam 07:00-20:00','Retrait au comptoir pâtisserie'),
        (14,'Fournil Guéliz [KULHA-LOADTEST]','BAKERY','31 avenue Mohammed V','Marrakech','Guéliz','+212600100014','Lun-Sam 06:30-20:00','Retrait à l’entrée principale'),
        (15,'Saveurs de la Médina [KULHA-LOADTEST]','RESTAURANT','10 derb Dabachi','Marrakech','Médina','+212600100015','Tous les jours 11:00-23:00','Demander le panier réservé'),
        (16,'Jardin Rouge Café [KULHA-LOADTEST]','CAFE','4 rue Ibn Aïcha','Marrakech','Hivernage','+212600100016','Tous les jours 08:00-22:00','Retrait côté terrasse'),
        (17,'Souk Frais Marrakech [KULHA-LOADTEST]','SUPERMARKET','2 boulevard Abdelkrim Khattabi','Marrakech','Semlalia','+212600100017','Tous les jours 08:00-22:00','Accueil du magasin'),
        (18,'Cuisine Atlas [KULHA-LOADTEST]','RESTAURANT','25 route de Safi','Marrakech','Sidi Youssef Ben Ali','+212600100018','Lun-Dim 11:00-22:30','Retrait au comptoir cuisine'),
        (19,'Boulangerie Tangerine [KULHA-LOADTEST]','BAKERY','16 boulevard Pasteur','Tanger','Centre Ville','+212600100019','Lun-Sam 07:00-20:00','Retrait au comptoir'),
        (20,'Café Malabata [KULHA-LOADTEST]','CAFE','8 avenue Mohammed VI','Tanger','Malabata','+212600100020','Tous les jours 08:00-22:00','Présenter la réservation au serveur'),
        (21,'Marché Iberia [KULHA-LOADTEST]','SUPERMARKET','3 rue de Belgique','Tanger','Iberia','+212600100021','Tous les jours 09:00-21:00','Retrait à l’accueil'),
        (22,'Dar El Bahja [KULHA-LOADTEST]','RESTAURANT','11 rue de la Plage','Tanger','Marshan','+212600100022','Lun-Dim 11:00-23:00','Retrait par l’entrée latérale'),
        (23,'Fournil Fassi [KULHA-LOADTEST]','BAKERY','28 avenue Hassan II','Fès','Ville Nouvelle','+212600100023','Lun-Sam 06:30-20:00','Retrait près du four'),
        (24,'Riad Saveurs [KULHA-LOADTEST]','RESTAURANT','7 derb Sidi Bou Jida','Fès','Médina','+212600100024','Tous les jours 11:00-22:00','Demander la commande KULHA'),
        (25,'Épicerie Atlas Fès [KULHA-LOADTEST]','OTHER','15 avenue des FAR','Fès','Atlas','+212600100025','Lun-Sam 08:00-21:00','Retrait à la caisse'),
        (26,'Café Narjiss [KULHA-LOADTEST]','CAFE','2 rue Al Qods','Fès','Narjiss','+212600100026','Tous les jours 07:30-22:00','Retrait au comptoir'),
        (27,'Boulangerie Souss [KULHA-LOADTEST]','BAKERY','10 avenue Hassan II','Agadir','Talborjt','+212600100027','Lun-Sam 06:30-20:00','Retrait au magasin'),
        (28,'La Table du Port [KULHA-LOADTEST]','RESTAURANT','4 boulevard du 20 Août','Agadir','Centre Ville','+212600100028','Tous les jours 11:00-23:00','Retrait à l’entrée du restaurant'),
        (29,'Fruits du Soleil [KULHA-LOADTEST]','OTHER','18 avenue des FAR','Agadir','Dakhla','+212600100029','Tous les jours 08:00-21:00','Retrait au stand fruits et légumes'),
        (30,'Market Founty [KULHA-LOADTEST]','SUPERMARKET','6 rue de l’Atlantique','Agadir','Founty','+212600100030','Tous les jours 08:00-22:00','Zone retrait près des caisses'),
        (31,'Pâtisserie Hamria [KULHA-LOADTEST]','BAKERY','21 avenue des FAR','Meknès','Hamria','+212600100031','Lun-Sam 07:00-20:00','Retrait au comptoir pâtisserie'),
        (32,'Cuisine Bab Mansour [KULHA-LOADTEST]','RESTAURANT','9 place El Hedim','Meknès','Médina','+212600100032','Tous les jours 11:00-22:30','Retrait à la porte principale'),
        (33,'Market Marjane Meknès [KULHA-LOADTEST]','SUPERMARKET','5 route de Fès','Meknès','Marjane','+212600100033','Tous les jours 08:00-22:00','Accueil du magasin'),
        (34,'Boulangerie Maamora [KULHA-LOADTEST]','BAKERY','13 avenue Mohammed V','Kénitra','Centre Ville','+212600100034','Lun-Sam 06:30-20:00','Retrait au fournil'),
        (35,'Café Bir Rami [KULHA-LOADTEST]','CAFE','3 avenue Bir Rami','Kénitra','Bir Rami','+212600100035','Tous les jours 08:00-22:00','Retrait au comptoir'),
        (36,'Marché Isly [KULHA-LOADTEST]','SUPERMARKET','8 boulevard Mohammed Derfoufi','Oujda','Centre Ville','+212600100036','Tous les jours 08:00-21:00','Accueil du marché'),
        (37,'Saveurs de l’Oriental [KULHA-LOADTEST]','RESTAURANT','14 rue Marrakech','Oujda','Lazaret','+212600100037','Lun-Dim 11:00-22:30','Retrait au comptoir cuisine'),
        (38,'Fournil Martil [KULHA-LOADTEST]','BAKERY','6 avenue Hassan II','Tétouan','Centre Ville','+212600100038','Lun-Sam 07:00-20:00','Retrait à la boulangerie'),
        (39,'Café Ensanche [KULHA-LOADTEST]','CAFE','17 avenue des FAR','Tétouan','Ensanche','+212600100039','Tous les jours 08:00-22:00','Retrait au comptoir'),
        (40,'Coopérative Tadla [KULHA-LOADTEST]','OTHER','2 avenue Mohammed V','Béni Mellal','Centre Ville','+212600100040','Lun-Sam 08:00-20:00','Retrait au point de vente')
    ) AS seed(merchant_no, name, business_type, address, city, district, phone, opening_hours, pickup_instructions)
) t;

INSERT INTO merchant.merchants (
    id, owner_user_id, name, business_type, address, city, district, phone,
    opening_hours, pickup_instructions, status, decision_reason, decided_by,
    decided_at, created_at, updated_at
)
SELECT id, owner_user_id, name, business_type, address, city, district, phone,
       opening_hours, pickup_instructions, 'APPROVED', NULL, NULL,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM _kulha_seed_merchants
ON CONFLICT (id) DO NOTHING;

DO $$
DECLARE
    found_merchants integer;
BEGIN
    SELECT count(*) INTO found_merchants
    FROM merchant.merchants m
    JOIN _kulha_seed_merchants s ON s.id = m.id
    WHERE m.status = 'APPROVED'
      AND m.name = s.name
      AND m.city = s.city
      AND m.district = s.district;

    IF found_merchants <> 40 THEN
        RAISE EXCEPTION 'Expected all 40 isolated seed merchants to exist and be APPROVED; found %. No offers were committed.', found_merchants;
    END IF;
END $$;

WITH run_clock AS MATERIALIZED (
    SELECT CURRENT_TIMESTAMP AS now_at
), numbered AS (
    SELECT g.i,
           ((g.i - 1) % 40) + 1 AS merchant_no,
           CASE
               WHEN ((g.i - 1) % 100) < 20 THEN 'BREAD_PASTRY'
               WHEN ((g.i - 1) % 100) < 41 THEN 'PREPARED_MEALS'
               WHEN ((g.i - 1) % 100) < 56 THEN 'GROCERIES'
               WHEN ((g.i - 1) % 100) < 73 THEN 'FRUIT_VEG'
               WHEN ((g.i - 1) % 100) < 83 THEN 'DRINKS'
               WHEN ((g.i - 1) % 100) < 96 THEN 'SURPRISE_BASKET'
               ELSE 'OTHER'
           END AS category,
           CASE
               WHEN (g.i * 37) % 100 < 65 THEN 'PUBLISHED'
               WHEN (g.i * 37) % 100 < 75 THEN 'EXPIRED'
               WHEN (g.i * 37) % 100 < 83 THEN 'UNPUBLISHED'
               WHEN (g.i * 37) % 100 < 90 THEN 'SUSPENDED'
               WHEN (g.i * 37) % 100 < 96 THEN 'DRAFT'
               ELSE 'CANCELLED'
           END AS status,
           CASE WHEN g.i % 3 = 0 THEN 'fr'
                WHEN g.i % 3 = 1 THEN 'ar'
                ELSE 'en' END AS language,
           20 + ((g.i * 19) % 46) AS discount_percent,
           1 + ((g.i * 13) % 40) AS quantity_total
    FROM generate_series(1, 1000) AS g(i)
), priced AS (
    SELECT n.*,
           CASE category
               WHEN 'BREAD_PASTRY' THEN 500 + ((i * 37) % 2500)
               WHEN 'PREPARED_MEALS' THEN 1600 + ((i * 53) % 6000)
               WHEN 'GROCERIES' THEN 700 + ((i * 43) % 5000)
               WHEN 'FRUIT_VEG' THEN 500 + ((i * 47) % 3500)
               WHEN 'DRINKS' THEN 300 + ((i * 29) % 1800)
               WHEN 'SURPRISE_BASKET' THEN 1200 + ((i * 71) % 8000)
               ELSE 500 + ((i * 31) % 2500)
           END::integer AS price_minor
    FROM numbered n
), stocked AS (
    SELECT p.*,
           CASE
               WHEN status IN ('DRAFT','CANCELLED') THEN 0
               WHEN i % 17 = 0 THEN quantity_total
               WHEN i % 7 = 0 THEN GREATEST(0, quantity_total - 1)
               WHEN i % 5 = 0 THEN quantity_total / 2
               ELSE (i * 3) % (quantity_total + 1)
           END::integer AS quantity_reserved,
           CASE WHEN i % 4 = 0 THEN 'UNKNOWN' ELSE 'DECLARED' END AS allergens_status,
           CASE
               WHEN i % 4 = 0 THEN ARRAY[]::varchar[]
               WHEN i % 5 = 0 THEN ARRAY['GLUTEN','MILK']::varchar[]
               WHEN i % 5 = 1 THEN ARRAY['EGGS']::varchar[]
               WHEN i % 5 = 2 THEN ARRAY['GLUTEN','SESAME']::varchar[]
               WHEN i % 5 = 3 THEN ARRAY['MILK','NUTS']::varchar[]
               ELSE ARRAY['SOY','MUSTARD']::varchar[]
           END AS allergens
    FROM priced p
), timed AS (
    SELECT s.*,
           CASE WHEN status = 'EXPIRED'
                THEN c.now_at - (20 - (i % 10)) * INTERVAL '1 day'
                ELSE c.now_at - (1 + ((i * 11) % 10)) * INTERVAL '1 day'
           END AS created_at,
           CASE
               WHEN status IN ('DRAFT','CANCELLED') THEN NULL
               WHEN status = 'EXPIRED'
                   THEN c.now_at - (1 + (i % 10)) * INTERVAL '1 day' - INTERVAL '6 hours'
               ELSE c.now_at + (1 + ((i * 17) % 96)) * INTERVAL '1 hour'
           END AS pickup_start,
           CASE
               WHEN status IN ('DRAFT','CANCELLED') THEN NULL
               WHEN status = 'EXPIRED'
                   THEN c.now_at - (1 + (i % 10)) * INTERVAL '1 day' - INTERVAL '3 hours'
               ELSE c.now_at + (1 + ((i * 17) % 96)) * INTERVAL '1 hour'
                    + (2 + ((i * 7) % 7)) * INTERVAL '1 hour'
           END AS pickup_end
    FROM stocked s
    CROSS JOIN run_clock c
), titled AS (
    SELECT t.*,
           CASE category
               WHEN 'BREAD_PASTRY' THEN CASE language
                   WHEN 'fr' THEN (ARRAY['Assortiment de viennoiseries du matin','Pain complet et baguettes du jour','Msemen et harcha maison','Pâtisseries marocaines assorties','Croissants pur beurre - lot du soir'])[(1 + ((i - 1) / 3) % 5)]
                   WHEN 'ar' THEN (ARRAY['سلة معجنات الصباح','خبز كامل وباغيت طازج','مسمن وحرشة منزلية','تشكيلة حلويات مغربية','كرواسان بالزبدة - عرض المساء'])[(1 + ((i - 1) / 3) % 5)]
                   ELSE (ARRAY['Morning pastry selection','Fresh wholegrain bread and baguettes','Homemade msemen and harcha','Moroccan pastry assortment','Butter croissants evening pack'])[(1 + ((i - 1) / 3) % 5)] END
               WHEN 'PREPARED_MEALS' THEN CASE language
                   WHEN 'fr' THEN (ARRAY['Tajine de poulet aux olives','Couscous aux sept légumes','Pastilla poulet et amandes','Harira et briouates du jour','Plat marocain à emporter'])[(1 + ((i - 1) / 3) % 5)]
                   WHEN 'ar' THEN (ARRAY['طاجين الدجاج بالزيتون','كسكس بسبع خضروات','بسطيلة بالدجاج واللوز','حريرة وبريوات اليوم','طبق مغربي جاهز للأخذ'])[(1 + ((i - 1) / 3) % 5)]
                   ELSE (ARRAY['Chicken tagine with olives','Seven-vegetable couscous','Chicken and almond pastilla','Harira soup and briouates','Moroccan dish takeaway'])[(1 + ((i - 1) / 3) % 5)] END
               WHEN 'GROCERIES' THEN CASE language
                   WHEN 'fr' THEN (ARRAY['Panier d’épicerie du quartier','Pack lait, yaourts et fromage','Sélection de produits secs','Épicerie du quotidien à prix réduit','Petit panier de provisions'])[(1 + ((i - 1) / 3) % 5)]
                   WHEN 'ar' THEN (ARRAY['سلة مواد غذائية من المتجر','حزمة الحليب والياغورت والجبن','تشكيلة مواد غذائية جافة','مواد أساسية بسعر مخفض','سلة صغيرة من المؤونة'])[(1 + ((i - 1) / 3) % 5)]
                   ELSE (ARRAY['Neighborhood grocery bundle','Milk, yogurt and cheese pack','Pantry staples selection','Everyday groceries at a reduced price','Small household provisions bag'])[(1 + ((i - 1) / 3) % 5)] END
               WHEN 'FRUIT_VEG' THEN CASE language
                   WHEN 'fr' THEN (ARRAY['Cagette de fruits de saison','Légumes frais du marché','Panier tomates, carottes et courgettes','Fruits mûrs à consommer rapidement','Sélection du maraîcher'])[(1 + ((i - 1) / 3) % 5)]
                   WHEN 'ar' THEN (ARRAY['صندوق فواكه موسمية','خضروات طازجة من السوق','سلة طماطم وجزر وكوسة','فواكه ناضجة للاستهلاك السريع','اختيار الخضّار اليومي'])[(1 + ((i - 1) / 3) % 5)]
                   ELSE (ARRAY['Seasonal fruit crate','Fresh market vegetables','Tomato, carrot and courgette box','Ripe fruit to enjoy today','Greengrocer’s daily selection'])[(1 + ((i - 1) / 3) % 5)] END
               WHEN 'DRINKS' THEN CASE language
                   WHEN 'fr' THEN (ARRAY['Jus d’orange pressé du jour','Pack de boissons fraîches','Jus maison et citronnade','Smoothies aux fruits frais','Boissons du comptoir - sélection'])[(1 + ((i - 1) / 3) % 5)]
                   WHEN 'ar' THEN (ARRAY['عصير برتقال طازج','حزمة مشروبات باردة','عصير منزلي وليمونادة','سموثي بالفواكه الطازجة','تشكيلة مشروبات اليوم'])[(1 + ((i - 1) / 3) % 5)]
                   ELSE (ARRAY['Freshly squeezed orange juice','Chilled drinks bundle','Homemade juice and lemonade','Fresh fruit smoothies','Counter drinks selection'])[(1 + ((i - 1) / 3) % 5)] END
               WHEN 'SURPRISE_BASKET' THEN CASE language
                   WHEN 'fr' THEN (ARRAY['Panier surprise anti-gaspi','Sac gourmand de fin de journée','Panier mixte à petit prix','Assortiment surprise du commerçant','Panier généreux à récupérer'])[(1 + ((i - 1) / 3) % 5)]
                   WHEN 'ar' THEN (ARRAY['سلة مفاجآت لمحاربة الهدر','كيس لذيذ لنهاية اليوم','سلة متنوعة بسعر مناسب','تشكيلة مفاجأة من المتجر','سلة وفيرة للاستلام'])[(1 + ((i - 1) / 3) % 5)]
                   ELSE (ARRAY['Surprise anti-waste basket','End-of-day food rescue bag','Mixed value basket','Merchant’s surprise selection','Generous pickup basket'])[(1 + ((i - 1) / 3) % 5)] END
               ELSE CASE language
                   WHEN 'fr' THEN (ARRAY['Sandwichs et snacks du comptoir','Mini-quiches et bouchées salées','Petits gâteaux et biscuits maison','Snack salé du jour','Assortiment de pauses gourmandes'])[(1 + ((i - 1) / 3) % 5)]
                   WHEN 'ar' THEN (ARRAY['سندويتشات ووجبات خفيفة','فطائر صغيرة ومقبلات مالحة','كعك وبسكويت منزلي','وجبة خفيفة مالحة لليوم','تشكيلة وجبات للاستراحة'])[(1 + ((i - 1) / 3) % 5)]
                   ELSE (ARRAY['Counter sandwiches and snacks','Mini quiches and savory bites','Homemade cakes and biscuits','Savory snack of the day','Snack-time assortment'])[(1 + ((i - 1) / 3) % 5)] END
           END AS title,
           CASE language
               WHEN 'fr' THEN (ARRAY[
                   'Préparé ou sélectionné aujourd’hui, avec une quantité limitée pour la collecte en boutique.',
                   'Une offre anti-gaspillage à réserver à l’avance et à récupérer pendant le créneau affiché.',
                   'Produits encore consommables, proposés à prix réduit avant la fermeture du commerce.',
                   'La composition varie selon les invendus disponibles au moment de la préparation.',
                   'À récupérer directement auprès du commerçant ; respectez l’horaire de retrait.',
                   'Une seconde chance pour de bons produits, avec un tarif réduit et un retrait local.'
               ])[(1 + (i % 6))]
               WHEN 'ar' THEN (ARRAY[
                   'تم إعدادها أو اختيارها اليوم بكمية محدودة للاستلام من المتجر.',
                   'عرض للحد من هدر الطعام؛ احجز مسبقاً واستلم خلال الفترة المحددة.',
                   'منتجات صالحة للاستهلاك مع تخفيض قبل إغلاق المتجر.',
                   'قد يختلف المحتوى حسب المنتجات المتبقية والمتاحة وقت التحضير.',
                   'يرجى الاستلام مباشرة من المتجر واحترام موعد الاستلام.',
                   'فرصة ثانية لمنتجات جيدة بسعر مخفض واستلام محلي.'
               ])[(1 + (i % 6))]
               ELSE (ARRAY[
                   'Prepared or selected today in limited quantities for in-store collection.',
                   'A food-saving deal to reserve ahead and collect during the displayed time window.',
                   'Good-to-enjoy products offered at a reduced price before the shop closes.',
                   'The exact mix may vary with the items left at the end of the day.',
                   'Collect directly from the merchant and arrive during the stated pickup window.',
                   'Give quality food a second chance with a lower price and a local pickup.'
               ])[(1 + (i % 6))]
           END AS description
    FROM timed t
), final_rows AS (
    SELECT t.*,
           CASE WHEN t.status IN ('PUBLISHED','EXPIRED','UNPUBLISHED','SUSPENDED')
                THEN t.created_at + INTERVAL '1 hour' ELSE NULL END AS published_at,
           CASE WHEN t.quantity_reserved > 0
                THEN t.created_at + INTERVAL '2 hours' ELSE NULL END AS reservation_started_at,
           CASE t.status WHEN 'PUBLISHED' THEN 1 WHEN 'EXPIRED' THEN 2
                WHEN 'UNPUBLISHED' THEN 2 WHEN 'SUSPENDED' THEN 2
                WHEN 'CANCELLED' THEN 1 ELSE 0 END AS version
    FROM titled t
)
INSERT INTO offer.offers (
    id, merchant_id, title, description, category, price_minor, original_price_minor,
    quantity_total, quantity_reserved, reservation_started_at, pickup_start, pickup_end,
    allergens_status, allergens, food_notes, status, published_at, version, created_at, updated_at
)
SELECT
    format('%s-%s-%s-%s-%s',
        substr(id_hash,1,8), substr(id_hash,9,4), substr(id_hash,13,4),
        substr(id_hash,17,4), substr(id_hash,21,12))::uuid AS id,
    m.id AS merchant_id,
    left(f.title, 120),
    left(f.description || ' — ' || m.name, 1000),
    f.category,
    f.price_minor,
    ceil(f.price_minor::numeric * 100 / (100 - f.discount_percent))::integer AS original_price_minor,
    f.quantity_total,
    f.quantity_reserved,
    f.reservation_started_at,
    f.pickup_start,
    f.pickup_end,
    f.allergens_status,
    f.allergens,
    'KULHA_LOADTEST_1000_V1:' || lpad(f.i::text, 4, '0') AS food_notes,
    f.status,
    f.published_at,
    f.version,
    f.created_at,
    c.now_at AS updated_at
FROM (
    SELECT r.*,
           md5('kulha-loadtest-v1:offer:' || r.i::text) AS id_hash
    FROM final_rows r
) f
JOIN _kulha_seed_merchants sm ON sm.merchant_no = f.merchant_no
JOIN merchant.merchants m ON m.id = sm.id AND m.status = 'APPROVED'
CROSS JOIN run_clock c
WHERE NOT EXISTS (
    SELECT 1 FROM offer.offers existing
    WHERE existing.food_notes = 'KULHA_LOADTEST_1000_V1:' || lpad(f.i::text, 4, '0')
)
ON CONFLICT (id) DO NOTHING;

DO $$
DECLARE
    offer_count integer;
    distinct_marker_count integer;
    merchant_count integer;
    missing_expected_count integer;
BEGIN
    SELECT count(*), count(DISTINCT food_notes) INTO offer_count, distinct_marker_count
    FROM offer.offers
    WHERE left(COALESCE(food_notes, ''), length('KULHA_LOADTEST_1000_V1:')) = 'KULHA_LOADTEST_1000_V1:';

    WITH expected AS (
        SELECT format('%s-%s-%s-%s-%s',
                   substr(h,1,8), substr(h,9,4), substr(h,13,4),
                   substr(h,17,4), substr(h,21,12))::uuid AS id,
               'KULHA_LOADTEST_1000_V1:' || lpad(n::text, 4, '0') AS marker
        FROM (
            SELECT n, md5('kulha-loadtest-v1:offer:' || n::text) AS h
            FROM generate_series(1,1000) AS n
        ) hashes
    )
    SELECT count(*) INTO missing_expected_count
    FROM expected e
    WHERE NOT EXISTS (
        SELECT 1 FROM offer.offers o
        WHERE o.id = e.id AND o.food_notes = e.marker
    );

    SELECT count(*) INTO merchant_count
    FROM merchant.merchants m
    JOIN _kulha_seed_merchants s ON s.id = m.id
    WHERE m.status = 'APPROVED';

    IF offer_count <> 1000 OR distinct_marker_count <> 1000 OR missing_expected_count <> 0 THEN
        RAISE EXCEPTION 'Seed integrity failed: tagged rows %, unique markers %, missing expected deterministic rows %. Transaction will roll back.',
            offer_count, distinct_marker_count, missing_expected_count;
    END IF;
    IF merchant_count <> 40 THEN
        RAISE EXCEPTION 'Seed did not leave exactly 40 approved test merchants (found %). Transaction will roll back.', merchant_count;
    END IF;

    RAISE NOTICE 'KULHA load-test seed ready: % offers across % isolated approved merchants.', offer_count, merchant_count;
END $$;

COMMIT;
