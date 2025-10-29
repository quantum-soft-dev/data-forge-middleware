/**
 * E2E Test for User Management Lock/Unlock (T050)
 *
 * Tests the complete user lifecycle:
 * 1. Admin logs in
 * 2. Admin navigates to User Management
 * 3. Admin creates a new account
 * 4. Admin locks the account
 * 5. New user attempts to login (should fail)
 * 6. Admin unlocks the account
 * 7. New user logs in successfully
 *
 * Note: This test requires a running backend with Keycloak integration
 */

import { test, expect, Page } from '@playwright/test'

/**
 * Helper: Admin login
 */
async function adminLogin(page: Page) {
  await page.goto('/login')

  // Wait for login button
  await page.waitForSelector('button:has-text("Sign In with Keycloak")', { timeout: 10000 })

  // Click login button
  await page.click('button:has-text("Sign In with Keycloak")')

  // Wait for Keycloak login page
  await page.waitForURL(/.*auth.*/, { timeout: 10000 })

  // Fill in admin credentials
  await page.fill('input[name="username"]', process.env.KEYCLOAK_ADMIN_USER || 'admin')
  await page.fill('input[name="password"]', process.env.KEYCLOAK_ADMIN_PASSWORD || 'admin')
  await page.click('input[type="submit"]')

  // Wait for redirect back to app
  await page.waitForURL(/.*dashboard.*/, { timeout: 10000 })
}

/**
 * Helper: User login attempt
 */
async function userLogin(page: Page, username: string, password: string): Promise<boolean> {
  try {
    await page.goto('/login')

    // Wait for login button
    await page.waitForSelector('button:has-text("Sign In with Keycloak")', { timeout: 10000 })

    // Click login button
    await page.click('button:has-text("Sign In with Keycloak")')

    // Wait for Keycloak login page
    await page.waitForURL(/.*auth.*/, { timeout: 10000 })

    // Fill in user credentials
    await page.fill('input[name="username"]', username)
    await page.fill('input[name="password"]', password)
    await page.click('input[type="submit"]')

    // Check if login was successful (redirect to dashboard)
    await page.waitForURL(/.*dashboard.*/, { timeout: 10000 })
    return true
  } catch (error) {
    // Login failed (expected when account is locked)
    return false
  }
}

/**
 * Helper: Admin logout
 */
async function logout(page: Page) {
  // Click user menu
  await page.click('button:has-text("Logout")').catch(() => {
    // Alternative: click profile dropdown then logout
    page.click('[data-testid="user-menu"]').then(() => {
      page.click('button:has-text("Logout")')
    })
  })

  // Wait for redirect to login
  await page.waitForURL(/.*login.*/, { timeout: 10000 })
}

test.describe('User Management Lock/Unlock E2E', () => {
  test.setTimeout(120000) // 2 minutes for full E2E test

  let testEmail: string
  let testPassword: string

  test.beforeEach(() => {
    // Generate unique test email
    const timestamp = Date.now()
    testEmail = `test.user.${timestamp}@example.com`
    testPassword = '' // Will be set after account creation
  })

  test('complete lock-unlock cycle with login verification', async ({ page, context }) => {
    // Step 1: Admin logs in
    await adminLogin(page)

    // Verify admin dashboard is loaded
    await expect(page).toHaveURL(/.*dashboard.*/)

    // Step 2: Navigate to User Management
    await page.click('a:has-text("User Management")').catch(async () => {
      // Alternative: navigate via URL
      await page.goto('/admin/users')
    })

    await expect(page).toHaveURL(/.*admin\/users.*/)

    // Step 3: Create a new account
    await page.click('button:has-text("Create Account")')

    // Wait for create account form/page
    await expect(page).toHaveURL(/.*accounts\/create.*/)

    // Fill in account details
    await page.fill('input[name="email"]', testEmail)
    await page.fill('input[name="name"]', 'Test User E2E')

    // Select role (USER)
    await page.selectOption('select[name="role"]', 'USER')

    // Submit form
    await page.click('button[type="submit"]:has-text("Create Account")')

    // Wait for success message and temporary password display
    await page.waitForSelector(':has-text("Temporary Password")', { timeout: 10000 })

    // Extract temporary password from modal/alert
    const passwordElement = await page.locator('[data-testid="temporary-password"]').textContent()
    testPassword = passwordElement || ''

    // Save password for later use
    expect(testPassword).toBeTruthy()
    expect(testPassword.length).toBeGreaterThan(10)

    // Close modal/alert
    await page.click('button:has-text("Close")').catch(async () => {
      await page.keyboard.press('Escape')
    })

    // Navigate back to user list
    await page.goto('/admin/users')

    // Step 4: Find and lock the newly created account
    // Search for the account
    await page.fill('input[placeholder*="Search"]', testEmail)
    await page.keyboard.press('Enter')

    // Wait for search results
    await page.waitForSelector(`td:has-text("${testEmail}")`, { timeout: 10000 })

    // Click on the account to open details
    await page.click(`td:has-text("${testEmail}")`)

    // Wait for account details page
    await expect(page).toHaveURL(/.*admin\/users\/.*/)

    // Verify account status is "Enabled"
    await expect(page.locator(':has-text("Enabled")')).toBeVisible()

    // Click lock button
    await page.click('button:has-text("Lock Account")')

    // Confirm in dialog
    await page.waitForSelector('role=dialog')
    await page.click('button:has-text("Lock Account")') // Confirm button in dialog

    // Wait for lock to complete
    await page.waitForSelector(':has-text("Disabled")', { timeout: 10000 })

    // Verify account status changed to "Disabled"
    await expect(page.locator(':has-text("Disabled")')).toBeVisible()

    // Step 5: Logout as admin
    await logout(page)

    // Step 6: Attempt to login as locked user (should fail)
    const lockedLoginSuccess = await userLogin(page, testEmail, testPassword)

    expect(lockedLoginSuccess).toBe(false)

    // Verify we're still on login/error page
    await expect(page).toHaveURL(/.*auth.*|.*login.*/)

    // Look for error message about account being disabled
    const errorVisible = await page.locator(':has-text("disabled")').isVisible().catch(() => false) ||
                         await page.locator(':has-text("locked")').isVisible().catch(() => false) ||
                         await page.locator(':has-text("Account is disabled")').isVisible().catch(() => false)

    expect(errorVisible).toBe(true)

    // Step 7: Admin logs back in
    await adminLogin(page)

    // Navigate back to the user's account details
    await page.goto('/admin/users')
    await page.fill('input[placeholder*="Search"]', testEmail)
    await page.keyboard.press('Enter')
    await page.click(`td:has-text("${testEmail}")`)

    // Verify account is still locked
    await expect(page.locator(':has-text("Disabled")')).toBeVisible()

    // Step 8: Unlock the account
    await page.click('button:has-text("Unlock Account")')

    // Confirm in dialog
    await page.waitForSelector('role=dialog')
    await page.click('button:has-text("Unlock Account")') // Confirm button in dialog

    // Wait for unlock to complete
    await page.waitForSelector(':has-text("Enabled")', { timeout: 10000 })

    // Verify account status changed back to "Enabled"
    await expect(page.locator(':has-text("Enabled")')).toBeVisible()

    // Step 9: Logout as admin
    await logout(page)

    // Step 10: Login as unlocked user (should succeed)
    const unlockedLoginSuccess = await userLogin(page, testEmail, testPassword)

    expect(unlockedLoginSuccess).toBe(true)

    // Verify we're on dashboard or password change page
    await expect(page).toHaveURL(/.*dashboard.*|.*password.*/)

    // If password change is required (temporary password), handle it
    const passwordChangeVisible = await page.locator(':has-text("Change Password")').isVisible().catch(() => false)

    if (passwordChangeVisible) {
      // This is expected for temporary passwords
      await expect(page.locator(':has-text("You must change your password")')).toBeVisible()

      // Fill in password change form
      const newPassword = 'NewSecurePass123!@#'
      await page.fill('input[name="password"]', testPassword) // Current password
      await page.fill('input[name="newPassword"]', newPassword)
      await page.fill('input[name="confirmPassword"]', newPassword)
      await page.click('button[type="submit"]:has-text("Change Password")')

      // Wait for redirect to dashboard
      await page.waitForURL(/.*dashboard.*/, { timeout: 10000 })
    }

    // Verify successful login
    await expect(page).toHaveURL(/.*dashboard.*/)
    await expect(page.locator(':has-text("Test User E2E")')).toBeVisible()

    console.log('✅ E2E Test Passed: Complete lock-unlock cycle with login verification')
  })

  test('locked account shows appropriate error message on login attempt', async ({ page }) => {
    // This is a focused test for locked account login behavior
    // Assumes a pre-existing locked test account

    const lockedEmail = process.env.E2E_LOCKED_USER_EMAIL || 'locked.user@example.com'
    const lockedPassword = process.env.E2E_LOCKED_USER_PASSWORD || 'password123'

    await page.goto('/login')
    await page.click('button:has-text("Sign In with Keycloak")')

    await page.waitForURL(/.*auth.*/)

    await page.fill('input[name="username"]', lockedEmail)
    await page.fill('input[name="password"]', lockedPassword)
    await page.click('input[type="submit"]')

    // Should stay on Keycloak error page or redirect to login with error
    await page.waitForSelector(':has-text("disabled")', { timeout: 5000 }).catch(() => {
      // Alternative error messages
      page.waitForSelector(':has-text("locked")')
    })

    // Verify we didn't get redirected to dashboard
    await expect(page).not.toHaveURL(/.*dashboard.*/)
  })
})

test.describe('User Management - Edge Cases', () => {
  test('admin cannot lock their own account', async ({ page }) => {
    // Login as admin
    await adminLogin(page)

    // Navigate to user management
    await page.goto('/admin/users')

    // Search for admin's own email
    const adminEmail = process.env.KEYCLOAK_ADMIN_USER || 'admin'
    await page.fill('input[placeholder*="Search"]', adminEmail)
    await page.keyboard.press('Enter')

    // Click on admin account
    await page.click(`td:has-text("${adminEmail}")`)

    // Try to find lock button
    const lockButton = page.locator('button:has-text("Lock Account")')
    const isLockButtonVisible = await lockButton.isVisible().catch(() => false)

    if (isLockButtonVisible) {
      const isLockButtonDisabled = await lockButton.isDisabled()
      expect(isLockButtonDisabled).toBe(true)

      // Or verify error message shows up
      const warningVisible = await page.locator(':has-text("Cannot lock your own account")').isVisible().catch(() => false)
      expect(warningVisible).toBe(true)
    }
  })

  test('cannot lock account that does not have Keycloak integration', async ({ page }) => {
    // Login as admin
    await adminLogin(page)

    // Navigate to user management
    await page.goto('/admin/users')

    // Look for an account without Keycloak integration
    const noKeycloakIndicator = page.locator(':has-text("No Keycloak Integration")').first()
    const hasAccountWithoutKeycloak = await noKeycloakIndicator.isVisible().catch(() => false)

    if (hasAccountWithoutKeycloak) {
      // Click on that account
      await noKeycloakIndicator.click()

      // Verify lock button is disabled
      const lockButton = page.locator('button:has-text("Lock Account")')
      await expect(lockButton).toBeDisabled()

      // Verify tooltip/title explains why
      const title = await lockButton.getAttribute('title')
      expect(title).toContain('Keycloak')
    }
  })
})
