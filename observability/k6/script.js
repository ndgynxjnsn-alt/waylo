// Demo load for the Waylo gateway: mostly tracking, some quotes, a few invalid requests.
import http from 'k6/http';
import { sleep } from 'k6';

export const options = {
  scenarios: {
    steady: { executor: 'constant-arrival-rate', rate: 10, timeUnit: '1s', duration: '24h', preAllocatedVUs: 20 },
  },
};

const url = `${__ENV.GATEWAY_URL}/request`;
const params = { headers: { 'Content-Type': 'application/json', 'X-API-Key': __ENV.API_KEY } };
const countries = ['DE', 'FR', 'NL', 'US', 'JP'];

function trackingNumber() {
  return 'JJD' + String(Math.floor(Math.random() * 1e9)).padStart(9, '0');
}

export default function () {
  const dice = Math.random();
  let body;
  if (dice < 0.65) {
    const n = 1 + Math.floor(Math.random() * 3);
    body = { type: 'track', trackingNumbers: Array.from({ length: n }, trackingNumber) };
  } else if (dice < 0.95) {
    body = {
      type: 'quote',
      priority: Math.random() < 0.2,
      parcel: { weightGrams: 100 + Math.floor(Math.random() * 20000), dimensionsMm: { length: 300, width: 200, height: 100 } },
      destination: { country: countries[Math.floor(Math.random() * countries.length)], postalCode: '10115' },
    };
  } else {
    body = { type: 'track' }; // missing field -> 400
  }
  http.post(url, JSON.stringify(body), params);
  sleep(0.1);
}
