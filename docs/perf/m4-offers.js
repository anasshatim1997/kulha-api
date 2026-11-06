import http from 'k6/http';
import { check, sleep } from 'k6';

const base = __ENV.API_BASE_URL || 'http://localhost:8080/api/v1';
const token = __ENV.MERCHANT_TOKEN || '';
const offerId = __ENV.OFFER_ID || '';
const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    readers: { executor: 'constant-vus', vus: 50, duration: '1m', exec: 'readOffers' },
    writers: { executor: 'constant-vus', vus: 20, duration: '1m', exec: 'toggleOffer' },
  },
  thresholds: { http_req_failed: ['rate<0.02'] },
};

export function setup() {
  if (!token || !offerId) throw new Error('Set MERCHANT_TOKEN and OFFER_ID for a seeded merchant test offer.');
  return { ready: true };
}

export function readOffers() {
  const response = http.get(`${base}/merchants/me/offers?page=0&size=20`, { headers, responseCallback: http.expectedStatuses(200) });
  check(response, { 'list status is 200': r => r.status === 200 });
  sleep(0.2);
}

export function toggleOffer() {
  const path = Math.random() < 0.5 ? 'publish' : 'unpublish';
  const response = http.post(`${base}/merchants/me/offers/${offerId}/${path}`, null, {
    headers,
    responseCallback: http.expectedStatuses(200, 409, 422),
  });
  check(response, { 'write status is 200 or conflict': r => r.status === 200 || r.status === 409 || r.status === 422 });
  sleep(0.5);
}
