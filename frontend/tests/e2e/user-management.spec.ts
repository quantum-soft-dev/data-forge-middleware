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

test.describe('User Management - Create Account Flow (T037)', () => {
  test.setTimeout(120000) // 2 minutes for full E2E test

  let testEmail: string

  test.beforeEach(() => {
    // Generate unique test email
    const timestamp = Date.now()
    testEmail = `e2e.create.${timestamp}@example.com`
  })

  test('create account with USER role - full flow', async ({ page }) => {
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

    // Step 3: Click "Create Account" button
    await page.click('button:has-text("Create Account")')

    // Wait for create account form/page
    await expect(page).toHaveURL(/.*accounts\/create.*/)

    // Step 4: Fill in account details with USER role
    await page.fill('input[name="email"]', testEmail)
    await page.fill('input[name="name"]', 'E2E Test User')
    await page.selectOption('select[name="role"]', 'USER')

    // Step 5: Submit form
    await page.click('button[type="submit"]:has-text("Create Account")')

    // Step 6: Wait for success message and temporary password display
    await page.waitForSelector(':has-text("Account Created Successfully")', { timeout: 10000 })

    // Verify temporary password is displayed
    await expect(page.locator(':has-text("Temporary Password")')).toBeVisible()

    // Extract temporary password for verification
    const passwordElement = await page.locator('[data-testid="temporary-password"]').textContent()
    const tempPassword = passwordElement || ''

    expect(tempPassword).toBeTruthy()
    expect(tempPassword.length).toBeGreaterThan(10)

    // Verify account email is shown in modal
    await expect(page.locator(`:has-text("${testEmail}")`)).toBeVisible()

    // Step 7: Close modal
    await page.click('button:has-text("Close")').catch(async () => {
      await page.keyboard.press('Escape')
    })

    // Wait for modal to close
    await page.waitForSelector(':has-text("Account Created Successfully")', { state: 'hidden', timeout: 5000 })

    // Step 8: Navigate to user list
    await page.goto('/admin/users')

    // Step 9: Search for newly created account
    await page.fill('input[placeholder*="Search"]', testEmail)
    await page.keyboard.press('Enter')

    // Wait for search results
    await page.waitForSelector(`td:has-text("${testEmail}")`, { timeout: 10000 })

    // Step 10: Verify account appears in list with USER role
    const userRow = page.locator(`tr:has-text("${testEmail}")`)
    await expect(userRow).toBeVisible()

    // Verify USER role badge is present
    const roleBadge = userRow.locator('[data-testid="role-badge"]')
    await expect(roleBadge).toHaveText('USER')

    // Verify account status is "Enabled"
    await expect(userRow.locator(':has-text("Enabled")')).toBeVisible()

    console.log('✅ E2E Test Passed: Create account with USER role - full flow')
  })

  test('create account with ADMIN role - full flow', async ({ page }) => {
    // Generate unique admin email
    const adminEmail = `e2e.admin.${Date.now()}@example.com`

    // Step 1: Admin logs in
    await adminLogin(page)

    // Step 2: Navigate to User Management
    await page.goto('/admin/users')

    // Step 3: Click "Create Account" button
    await page.click('button:has-text("Create Account")')

    await expect(page).toHaveURL(/.*accounts\/create.*/)

    // Step 4: Fill in account details with ADMIN role
    await page.fill('input[name="email"]', adminEmail)
    await page.fill('input[name="name"]', 'E2E Test Admin')
    await page.selectOption('select[name="role"]', 'ADMIN')

    // Step 5: Submit form
    await page.click('button[type="submit"]:has-text("Create Account")')

    // Step 6: Verify success message and temporary password
    await page.waitForSelector(':has-text("Account Created Successfully")', { timeout: 10000 })

    const passwordElement = await page.locator('[data-testid="temporary-password"]').textContent()
    const tempPassword = passwordElement || ''

    expect(tempPassword).toBeTruthy()

    // Close modal
    await page.click('button:has-text("Close")').catch(async () => {
      await page.keyboard.press('Escape')
    })

    // Step 7: Navigate to user list and search
    await page.goto('/admin/users')
    await page.fill('input[placeholder*="Search"]', adminEmail)
    await page.keyboard.press('Enter')

    // Wait for search results
    await page.waitForSelector(`td:has-text("${adminEmail}")`, { timeout: 10000 })

    // Step 8: Verify account appears with ADMIN role badge
    const adminRow = page.locator(`tr:has-text("${adminEmail}")`)
    await expect(adminRow).toBeVisible()

    // Verify ADMIN role badge is present and styled differently
    const roleBadge = adminRow.locator('[data-testid="role-badge"]')
    await expect(roleBadge).toHaveText('ADMIN')

    // Verify badge has different styling (e.g., different color for ADMIN)
    const badgeClass = await roleBadge.getAttribute('class')
    expect(badgeClass).toContain('admin') // Assumes ADMIN badge has 'admin' class

    console.log('✅ E2E Test Passed: Create account with ADMIN role - full flow')
  })

  test('create account form validation errors', async ({ page }) => {
    // Login as admin
    await adminLogin(page)

    // Navigate to create account page
    await page.goto('/admin/accounts/create')

    // Try to submit without filling fields
    await page.click('button[type="submit"]:has-text("Create Account")')

    // Verify validation errors are displayed
    await expect(page.locator(':has-text("Email is required")')).toBeVisible()
    await expect(page.locator(':has-text("Name is required")')).toBeVisible()
    await expect(page.locator(':has-text("Role is required")')).toBeVisible()

    // Verify form did not submit (still on create page)
    await expect(page).toHaveURL(/.*accounts\/create.*/)

    console.log('✅ E2E Test Passed: Create account form validation errors')
  })

  test('create account with duplicate email shows error', async ({ page }) => {
    // Login as admin
    await adminLogin(page)

    // Navigate to user management
    await page.goto('/admin/users')

    // Get first existing user email from the list
    const firstEmailCell = page.locator('tbody tr td').nth(1) // Assuming email is in 2nd column
    const existingEmail = await firstEmailCell.textContent()

    if (!existingEmail) {
      console.log('⚠️ No existing users found, skipping duplicate email test')
      return
    }

    // Navigate to create account page
    await page.click('button:has-text("Create Account")')

    // Fill form with duplicate email
    await page.fill('input[name="email"]', existingEmail.trim())
    await page.fill('input[name="name"]', 'Duplicate User')
    await page.selectOption('select[name="role"]', 'USER')

    // Submit form
    await page.click('button[type="submit"]:has-text("Create Account")')

    // Verify error message is displayed
    await page.waitForSelector(':has-text("already exists")', { timeout: 10000 })

    // Verify modal did NOT open (no temporary password shown)
    await expect(page.locator(':has-text("Account Created Successfully")')).not.toBeVisible()

    console.log('✅ E2E Test Passed: Create account with duplicate email shows error')
  })

  test('cancel create account returns to user list', async ({ page }) => {
    // Login as admin
    await adminLogin(page)

    // Navigate to create account page
    await page.goto('/admin/accounts/create')

    // Fill in some data
    await page.fill('input[name="email"]', 'test@example.com')
    await page.fill('input[name="name"]', 'Test User')

    // Click cancel button
    await page.click('button:has-text("Cancel")')

    // Verify navigation back to user list
    await expect(page).toHaveURL(/.*admin\/users.*/)

    // Verify account was NOT created (search for the email)
    await page.fill('input[placeholder*="Search"]', 'test@example.com')
    await page.keyboard.press('Enter')

    // Wait a moment for search
    await page.waitForTimeout(1000)

    // Verify "No results" or account is not in the list
    const noResults = await page.locator(':has-text("No results")').isVisible().catch(() => false)
    const accountNotFound = await page.locator(`td:has-text("test@example.com")`).isVisible().catch(() => false)

    expect(noResults || !accountNotFound).toBe(true)

    console.log('✅ E2E Test Passed: Cancel create account returns to user list')
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
