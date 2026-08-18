// k6 load test: direct-grant login -> N refreshes -> logout
// Mimics the hot paths PhaseTwo benchmarked (auth-code w/o browser steps)
//
// Env vars:
//   BASE_URL   default http://localhost:8080
//   REALM      default bench
//   CLIENT_ID  default bench-client
//   CLIENT_SECRET default bench-secret
//   USERS_MAX  default 10  (number of pre-imported users user1..user{N})
//   REFRESHES  default 5
//   LOGOUT_RATE default 0.6
//   VUS        default 50
//   DURATION   default 3m

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL      = __ENV.BASE_URL      || 'http://localhost:8080';
const REALM         = __ENV.REALM         || 'bench';
const CLIENT_ID     = __ENV.CLIENT_ID     || 'bench-client';
const CLIENT_SECRET = __ENV.CLIENT_SECRET || 'bench-secret';
const USERS_MAX     = parseInt(__ENV.USERS_MAX || '10', 10);
const REFRESHES     = parseInt(__ENV.REFRESHES || '5', 10);
const LOGOUT_RATE   = parseFloat(__ENV.LOGOUT_RATE || '0.6');
const VUS           = parseInt(__ENV.VUS || '50', 10);
const DURATION      = __ENV.DURATION || '3m';

const tokenUrl  = `${BASE_URL}/realms/${REALM}/protocol/openid-connect/token`;
const logoutUrl = `${BASE_URL}/realms/${REALM}/protocol/openid-connect/logout`;

const loginTrend   = new Trend('flow_login', true);
const refreshTrend = new Trend('flow_refresh', true);
const logoutTrend  = new Trend('flow_logout', true);
const flowOk       = new Rate('flow_success');
const loginErr     = new Counter('login_errors');
const refreshErr   = new Counter('refresh_errors');
const logoutErr    = new Counter('logout_errors');

export const options = {
  scenarios: {
    auth_flow: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      gracefulStop: '10s',
    },
  },
  thresholds: {
    'flow_success': ['rate>0.99'],
    'flow_login{tag:p99}': [],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function form(obj) {
  return Object.entries(obj).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
}

const tokenHeaders = {
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  tags: { name: 'token' },
};

export default function () {
  const userIdx = randomIntBetween(1, USERS_MAX);
  const username = `user${userIdx}`;
  let success = true;

  // 1) Login (password grant)
  const loginBody = form({
    grant_type:    'password',
    client_id:     CLIENT_ID,
    client_secret: CLIENT_SECRET,
    username,
    password:      'pw',
    scope:         'openid',
  });
  const t0 = Date.now();
  const loginRes = http.post(tokenUrl, loginBody, tokenHeaders);
  loginTrend.add(Date.now() - t0);
  const loginOk = check(loginRes, {
    'login 200': (r) => r.status === 200,
    'login has refresh_token': (r) => !!(r.json() && r.json().refresh_token),
  });
  if (!loginOk) { loginErr.add(1); flowOk.add(false); return; }
  let refreshToken = loginRes.json().refresh_token;

  // 2) Refresh N times
  for (let i = 0; i < REFRESHES; i++) {
    const refreshBody = form({
      grant_type:    'refresh_token',
      client_id:     CLIENT_ID,
      client_secret: CLIENT_SECRET,
      refresh_token: refreshToken,
    });
    const t1 = Date.now();
    const refreshRes = http.post(tokenUrl, refreshBody, tokenHeaders);
    refreshTrend.add(Date.now() - t1);
    const refreshOk = check(refreshRes, {
      'refresh 200': (r) => r.status === 200,
      'refresh has refresh_token': (r) => !!(r.json() && r.json().refresh_token),
    });
    if (!refreshOk) {
      refreshErr.add(1);
      success = false;
      flowOk.add(false);
      return;
    }
    refreshToken = refreshRes.json().refresh_token;
    sleep(0.05);
  }

  // 3) Logout (60% of the time, configurable)
  if (Math.random() < LOGOUT_RATE) {
    const logoutBody = form({
      client_id:     CLIENT_ID,
      client_secret: CLIENT_SECRET,
      refresh_token: refreshToken,
    });
    const t2 = Date.now();
    const logoutRes = http.post(logoutUrl, logoutBody, tokenHeaders);
    logoutTrend.add(Date.now() - t2);
    const logoutOk = check(logoutRes, {
      'logout 2xx/3xx': (r) => r.status >= 200 && r.status < 400,
    });
    if (!logoutOk) { logoutErr.add(1); success = false; }
  }

  flowOk.add(success);
}
