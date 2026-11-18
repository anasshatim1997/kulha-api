SELECT m.name, m.district, m.latitude, m.longitude,
       count(o.id) FILTER (WHERE o.status='PUBLISHED' AND o.pickup_end > now()) AS active_offers,
       min(o.price_minor) FILTER (WHERE o.status='PUBLISHED' AND o.pickup_end > now()) AS lowest_price_minor
FROM merchant.merchants m
LEFT JOIN offer.offers o ON o.merchant_id=m.id
WHERE m.name LIKE '%[KULHA MAP DEMO]%'
GROUP BY m.id, m.name, m.district, m.latitude, m.longitude
ORDER BY m.district;

SELECT count(*) AS active_demo_offers
FROM offer.offers
WHERE id IN (SELECT md5('kulha-map-demo-v1:offer:' || n::text)::uuid FROM generate_series(1,16) n)
  AND status='PUBLISHED' AND pickup_end > now();
