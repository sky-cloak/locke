// Token-grant load for the local validation rig. Each iteration is a direct-access-grant
// token request on the master realm, which exercises the Redis-backed auth-session path,
// so it errors (and we measure how fast) when Redis is unreachable.
import http from 'k6/http';
import { Trend, Rate } from 'k6/metrics';

const base = __ENV.BASE || 'http://localhost:18088';
const vus = Number(__ENV.VUS || 20);
const duration = __ENV.DURATION || '60s';

export const options = {
  scenarios: {
    load: { executor: 'constant-vus', vus: vus, duration: duration },
  },
  // Don't let a single slow/failed request abort; we WANT to measure failures.
  thresholds: {},
};

const tokenLatency = new Trend('token_latency_ms', true);
const tokenErrors = new Rate('token_errors');

export default function () {
  const res = http.post(
    `${base}/realms/master/protocol/openid-connect/token`,
    { client_id: 'admin-cli', username: 'admin', password: 'admin', grant_type: 'password' },
    { timeout: '30s', tags: { name: 'token' } }
  );
  tokenLatency.add(res.timings.duration);
  tokenErrors.add(res.status !== 200);
}
