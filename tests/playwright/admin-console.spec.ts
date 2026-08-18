import { test, expect } from '@playwright/test';

const KC_URL = process.env.KC_URL || 'http://localhost:8080';
const ADMIN_USER = process.env.KC_ADMIN_USER || 'admin';
const ADMIN_PASS = process.env.KC_ADMIN_PASS || 'admin';

async function adminLogin(page) {
  await page.goto(`${KC_URL}/admin/master/console/`);
  // Wait for either the login form or the admin console to appear
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  // If on login page, fill in credentials
  const usernameField = page.locator('#username');
  if (await usernameField.isVisible({ timeout: 5000 }).catch(() => false)) {
    await usernameField.fill(ADMIN_USER);
    await page.fill('#password', ADMIN_PASS);
    await page.click('#kc-login');
  }
  // Wait for admin console to load
  await page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {});
  await expect(page.getByText('Welcome to Keycloak')).toBeVisible({ timeout: 30000 });
}

test.describe('Keycloak Admin Console (Redis Cache)', () => {

  test('admin console login page loads', async ({ page }) => {
    await page.goto(`${KC_URL}/admin/master/console/`);
    await page.waitForURL(/.*\/realms\/master\/protocol\/openid-connect\/auth.*/);
    await expect(page.locator('#username')).toBeVisible();
    await expect(page.locator('#password')).toBeVisible();
  });

  test('admin can log in to console', async ({ page }) => {
    await adminLogin(page);
    await expect(page).toHaveTitle(/Keycloak/i);
    await expect(page.getByText('Welcome to Keycloak')).toBeVisible({ timeout: 10000 });
  });

  test('admin dashboard loads with server info', async ({ page }) => {
    await adminLogin(page);
    // Click Server info tab
    await page.getByRole('tab', { name: /server info/i }).click();
    await expect(page.getByText('Version', { exact: false }).first()).toBeVisible({ timeout: 10000 });
  });

  test('can navigate to Clients section', async ({ page }) => {
    await adminLogin(page);
    await page.getByRole('link', { name: 'Clients' }).click();
    // Should see the default clients table
    await expect(page.getByText('admin-cli')).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('security-admin-console')).toBeVisible({ timeout: 10000 });
  });

  test('can navigate to Users section', async ({ page }) => {
    await adminLogin(page);
    await page.getByRole('link', { name: 'Users' }).click();

    // Click "View all users" if visible
    const viewAll = page.getByRole('button', { name: /view all/i });
    if (await viewAll.isVisible({ timeout: 3000 }).catch(() => false)) {
      await viewAll.click();
    }

    // Should see the admin user in the users table
    await expect(page.locator('table').getByText(ADMIN_USER).first()).toBeVisible({ timeout: 10000 });
  });

  test('can create a new realm', async ({ page }) => {
    await adminLogin(page);

    // Use the Admin REST API to create the realm (more reliable than UI selectors)
    const tokenRes = await page.request.post(`${KC_URL}/realms/master/protocol/openid-connect/token`, {
      form: {
        grant_type: 'password',
        client_id: 'admin-cli',
        username: ADMIN_USER,
        password: ADMIN_PASS,
      },
    });
    const tokenData = await tokenRes.json();
    const createRes = await page.request.post(`${KC_URL}/admin/realms`, {
      headers: { Authorization: `Bearer ${tokenData.access_token}`, 'Content-Type': 'application/json' },
      data: { realm: 'test-playwright', enabled: true, sslRequired: 'NONE' },
    });
    expect([201, 409]).toContain(createRes.status()); // 409 if already exists
    // Ensure sslRequired=NONE even if realm already existed
    if (createRes.status() === 409) {
      await page.request.put(`${KC_URL}/admin/realms/test-playwright`, {
        headers: { Authorization: `Bearer ${tokenData.access_token}`, 'Content-Type': 'application/json' },
        data: { sslRequired: 'NONE' },
      });
    }

    // Navigate to the new realm in the admin console
    await page.goto(`${KC_URL}/admin/test-playwright/console/`);
    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
    await expect(page.getByText('test-playwright')).toBeVisible({ timeout: 10000 });
  });

  test('can create a user in test realm', async ({ request }) => {
    // Use the Admin REST API to create a user (avoids realm-switching UI complexity)
    const tokenRes = await request.post(`${KC_URL}/realms/master/protocol/openid-connect/token`, {
      form: {
        grant_type: 'password',
        client_id: 'admin-cli',
        username: ADMIN_USER,
        password: ADMIN_PASS,
      },
    });
    const tokenData = await tokenRes.json();
    const headers = { Authorization: `Bearer ${tokenData.access_token}`, 'Content-Type': 'application/json' };

    const createRes = await request.post(`${KC_URL}/admin/realms/test-playwright/users`, {
      headers,
      data: {
        username: 'testuser1',
        email: 'testuser1@example.com',
        firstName: 'Test',
        lastName: 'User',
        enabled: true,
      },
    });
    expect([201, 409]).toContain(createRes.status());

    // Verify user exists
    const usersRes = await request.get(`${KC_URL}/admin/realms/test-playwright/users?username=testuser1`, { headers });
    expect(usersRes.ok()).toBeTruthy();
    const users = await usersRes.json();
    expect(users.length).toBeGreaterThan(0);
    expect(users[0].username).toBe('testuser1');

  });

  test('can navigate to Realm Settings', async ({ page }) => {
    await adminLogin(page);
    await page.getByRole('link', { name: 'Realm settings' }).click();
    await expect(page.getByRole('tab', { name: /general/i })).toBeVisible({ timeout: 10000 });
  });

  test('serverinfo API returns 200 with valid data', async ({ request }) => {
    const tokenRes = await request.post(`${KC_URL}/realms/master/protocol/openid-connect/token`, {
      form: {
        grant_type: 'password',
        client_id: 'admin-cli',
        username: ADMIN_USER,
        password: ADMIN_PASS,
      },
    });
    expect(tokenRes.ok()).toBeTruthy();
    const tokenData = await tokenRes.json();

    const infoRes = await request.get(`${KC_URL}/admin/serverinfo`, {
      headers: { Authorization: `Bearer ${tokenData.access_token}` },
    });
    expect(infoRes.ok()).toBeTruthy();
    const info = await infoRes.json();
    expect(info.systemInfo).toBeDefined();
    expect(info.systemInfo.version).toBeDefined();
  });

  test('OIDC discovery endpoint works', async ({ request }) => {
    const res = await request.get(`${KC_URL}/realms/master/.well-known/openid-configuration`);
    expect(res.ok()).toBeTruthy();
    const config = await res.json();
    expect(config.issuer).toBe(`${KC_URL}/realms/master`);
    expect(config.token_endpoint).toContain('/protocol/openid-connect/token');
    expect(config.authorization_endpoint).toContain('/protocol/openid-connect/auth');
  });

  test('cleanup: delete test-playwright realm', async ({ request }) => {
    const tokenRes = await request.post(`${KC_URL}/realms/master/protocol/openid-connect/token`, {
      form: {
        grant_type: 'password',
        client_id: 'admin-cli',
        username: ADMIN_USER,
        password: ADMIN_PASS,
      },
    });
    const tokenData = await tokenRes.json();

    const res = await request.delete(`${KC_URL}/admin/realms/test-playwright`, {
      headers: { Authorization: `Bearer ${tokenData.access_token}` },
    });
    expect([204, 404]).toContain(res.status());
  });
});
