# Frontend Security

## XSS (Cross-Site Scripting) Protection

### Built-in React Protection

This application is protected against XSS attacks through multiple layers:

#### 1. **React JSX Auto-Escaping** (Primary Defense)
React automatically escapes all values rendered in JSX expressions, converting HTML special characters to their entity equivalents.

**Example:**
```tsx
// User input: <script>alert('XSS')</script>
<p>{account.name}</p>
// Rendered as: &lt;script&gt;alert('XSS')&lt;/script&gt;
// Displays as text, not executed as code
```

**Verification:**
- ✅ No `dangerouslySetInnerHTML` usage in src/ directory
- ✅ No `.innerHTML` DOM manipulation
- ✅ All user data rendered via safe JSX expressions: `{account.name}`, `{account.email}`, `{account.phone}`, `{account.company}`

#### 2. **Input Validation** (Secondary Defense)
Client-side validation using React Hook Form + Zod schemas:

**Account Forms:**
- `CreateAccountForm.tsx`: Validates all fields before submission
- `EditAccountForm.tsx`: Validates all fields before update
- Zod schemas enforce type safety and format validation

**Example Schema:**
```typescript
// createAccountSchema.ts
z.object({
  name: z.string().min(1, 'Name is required'),
  email: z.string().email('Invalid email format'),
  phone: z.string().optional(),
  company: z.string().optional(),
})
```

#### 3. **Backend Validation** (Final Defense Layer)
Server-side validation prevents malicious data from being persisted:

**Value Objects with Validation:**
- `Phone.java`: E.164 format validation via regex
- `Company.java`: Length validation (2-255 characters)
- Both reject invalid input before database write

**DTO Validation:**
- `CreateAccountRequestDto`: Jakarta Bean Validation annotations
  - `@NotBlank`, `@Email`, `@Size` constraints
  - Automatic validation via `@Valid` annotation
- `UpdateAccountRequestDto`: Same validation rules

**Result:** Even if malicious data bypasses client validation, backend validation prevents storage.

#### 4. **Database Safety**
- PostgreSQL parameterized queries via JPA prevent SQL injection
- String fields stored as-is (validation ensures safety)
- No HTML rendering from database values (API returns JSON)

### Attack Surface Analysis

#### ✅ Protected Entry Points
1. **Account Creation Form**: Input → Zod validation → Backend DTO validation → Phone/Company Value Objects
2. **Account Edit Form**: Input → Zod validation → Backend DTO validation → Phone/Company Value Objects
3. **Account Display**: Database → DTO mapping → JSON API → React JSX auto-escaping

#### ✅ Protected Rendering Locations
- `/features/account-delete/DeleteAccountDialog.tsx:81` - `{account.name}`
- `/features/account-delete/DeleteAccountDialog.tsx:85` - `{account.email}`
- All account list items, table cells, and form fields

#### 🚫 Dangerous Patterns (Audited - None Found)
- ❌ `dangerouslySetInnerHTML` - **Not used**
- ❌ `.innerHTML` - **Not used**
- ❌ `eval()` - **Not used**
- ❌ Unescaped template literals in HTML - **Not used**

### Content Security Policy (CSP)

**Recommendation for Production Deployment:**

Add the following HTTP headers to further harden against XSS:

```http
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self' https://your-tenant.us.auth0.com https://api.domain.com; worker-src 'self' blob:
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-XSS-Protection: 1; mode=block
```

Auth0 needs both of the last two directives: `connect-src` for the
`/oauth/token` calls auth0-spa-js makes from the page, and `blob:` in
`worker-src` because with `cacheLocation: "memory"` the SDK builds its token
worker from a blob URL — without it `new Worker()` throws and the app renders
a blank page.

**Configuration:** Add to the reverse proxy (nginx/Apache); see
`nginx.conf.example` for a working header.

### Testing XSS Protection

**Manual Testing:**
```bash
# Create account with XSS payload
POST /api/admin/accounts
{
  "name": "<script>alert('XSS')</script>",
  "email": "<img src=x onerror=alert('XSS')>@example.com",
  "company": "'; DROP TABLE accounts; --"
}

# Expected Behavior:
# 1. Backend validation rejects invalid email format (400 Bad Request)
# 2. If accepted, React renders as text (not executed)
# 3. Company field stored but rendered safely in UI
```

**Automated Testing:**
Consider adding E2E tests with XSS payloads (Playwright):
```typescript
test('should escape XSS in account name', async ({ page }) => {
  await createAccount({ name: '<script>alert("XSS")</script>' })
  await page.goto('/accounts')
  await expect(page.locator('text=<script>alert("XSS")</script>')).toBeVisible()
  // Script tag should be visible as text, not executed
})
```

### Conclusion

**Status:** ✅ **Application is protected against XSS attacks**

**Protection Mechanisms:**
1. React JSX auto-escaping (default behavior)
2. Client-side validation (Zod schemas)
3. Server-side validation (DTOs + Value Objects)
4. No dangerous patterns in codebase

**No DOMPurify library needed** - React's built-in protection is sufficient for this use case.

---

**Last Reviewed:** 2025-10-12
**Reviewed By:** Security Audit (PR #10 Review Response)
**Audit Scope:** All frontend rendering of user-controlled data
