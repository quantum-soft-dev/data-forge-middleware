# Device Authorization Flow - Client Integration Guide

## Overview

This guide describes how to implement the Device Authorization Flow (RFC 8628) for devices/applications that upload files to Data Forge Middleware via the Batch API.

**Use Cases:**
- IoT devices without keyboard/browser
- CLI tools
- Background services
- Embedded systems

**Flow Summary:**
1. Device requests authorization with site name
2. Device displays user code
3. User opens browser, enters code, approves
4. Device polls for credentials
5. Device uses credentials for Batch API uploads

---

## API Base URL

| Environment | URL |
|-------------|-----|
| Production | `<PRODUCTION_URL>` (TBD) |
| Development | `https://dev.dfm.bitbi.io` |

---

## Step 1: Initiate Authorization

**Endpoint:** `POST /api/v1/device/authorize`

**Authentication:** None (public endpoint)

**Request:**
```json
{
  "siteName": "warehouse-01",
  "siteDescription": "Main warehouse terminal"
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `siteName` | string | Yes | 1-100 chars, alphanumeric + hyphens, cannot start/end with hyphen |
| `siteDescription` | string | No | Max 500 chars |

**Response (200 OK):**
```json
{
  "deviceCode": "GmRhmhcxhwAzkoEqiMEg_DnyEysNkuNhszIySk9eS",
  "userCode": "WDJB-MJHT",
  "verificationUri": "https://dev.dfm.bitbi.io/device-verify",
  "verificationUriComplete": "https://dev.dfm.bitbi.io/device-verify?code=WDJB-MJHT",
  "expiresIn": 900,
  "interval": 5
}
```

| Field | Description |
|-------|-------------|
| `deviceCode` | Secret code for polling (64 chars). **Keep secret!** |
| `userCode` | Code to display to user (format: XXXX-XXXX) |
| `verificationUri` | URL where user enters code |
| `verificationUriComplete` | URL with code pre-filled (for QR codes) |
| `expiresIn` | Seconds until codes expire (default: 900 = 15 min) |
| `interval` | Recommended polling interval in seconds |

**Error Response (400):**
```json
{
  "error": "invalid_request",
  "error_description": "siteName is required"
}
```

---

## Step 2: Display Code to User

Show the user code and verification URL to the user:

```
===================================
    Device Authorization Required
===================================

Open this URL in your browser:
  https://dev.dfm.bitbi.io/device-verify

Enter this code:
  WDJB-MJHT

This code expires in 15 minutes.
===================================
```

**Tips:**
- Display `userCode` prominently
- Print `verificationUriComplete` as well: opening it fills the code in for the
  operator, so the code never has to be retyped. The parameter survives the
  Auth0 round trip a cold browser session takes, and the code in it may be given
  in any case and with or without the separator — the page normalizes it before
  it looks anything up (issue #211)
- Consider generating QR code from `verificationUriComplete`
- Show expiration countdown

---

## Step 3: Poll for Credentials

**Endpoint:** `POST /api/v1/device/token`

**Authentication:** None (public endpoint)

**Request:**
```json
{
  "deviceCode": "GmRhmhcxhwAzkoEqiMEg_DnyEysNkuNhszIySk9eS"
}
```

### Success Response (200 OK)

User has approved. Site created. Tokens returned:

```json
{
  "siteId": "550e8400-e29b-41d4-a716-446655440000",
  "siteName": "warehouse-01",
  "accessToken": "eyJhbGci...",
  "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2g...",
  "accessTokenExpiresAt": "2026-02-21T11:35:00Z",
  "refreshTokenExpiresAt": "2026-05-22T10:35:00Z",
  "apiBaseUrl": "https://dev.dfm.bitbi.io"
}
```

| Field | Description |
|-------|-------------|
| `siteId` | UUID of created site |
| `siteName` | Site name |
| `accessToken` | JWT for Batch API calls (valid ~1 hour) |
| `refreshToken` | Opaque token for obtaining new access tokens (valid ~90 days, 43 chars) |
| `accessTokenExpiresAt` | ISO 8601 timestamp when access token expires |
| `refreshTokenExpiresAt` | ISO 8601 timestamp when refresh token expires |
| `apiBaseUrl` | Base URL for API requests |

### Error Responses (400)

| Error Code | Description | Action |
|------------|-------------|--------|
| `authorization_pending` | User hasn't approved yet | Continue polling |
| `access_denied` | User denied the request | Stop. Show error. |
| `expired_token` | Codes expired (15 min) | Stop. Restart from Step 1. |
| `invalid_grant` | Invalid device code | Stop. Check code. |

**Example - Pending:**
```json
{
  "error": "authorization_pending",
  "error_description": "The user has not yet completed authorization"
}
```

**Example - Denied:**
```json
{
  "error": "access_denied",
  "error_description": "The user denied the authorization request"
}
```

**Example - Expired:**
```json
{
  "error": "expired_token",
  "error_description": "The device code has expired"
}
```

---

## Step 4: Use Credentials for Batch API

Once you have tokens, use the `accessToken` for all Batch API calls:

### Authentication

```
Authorization: Bearer {accessToken}
```

### Example: Initiate Batch Upload

```bash
curl -X POST "https://dev.dfm.bitbi.io/api/v1/device/batches" \
  -H "Authorization: Bearer eyJhbGci..." \
  -H "Content-Type: application/json"
```

---

## Step 5: Token Refresh

Access tokens expire after ~1 hour. When `accessTokenExpiresAt` is reached or you receive a `401 Unauthorized`, refresh the token.

**Endpoint:** `POST /api/v1/device/auth/refresh`

**Authentication:** None (public endpoint)

**Request:**
```json
{
  "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2g..."
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `refreshToken` | string | Yes | 43 chars, pattern `^[A-Za-z0-9_-]{43}$` |

**Response (200 OK):**
```json
{
  "accessToken": "eyJhbGci...",
  "refreshToken": "bmV3UmVmcmVzaFRva2Vu...",
  "accessTokenExpiresAt": "2026-02-21T12:35:00Z",
  "refreshTokenExpiresAt": "2026-05-22T10:35:00Z"
}
```

> **Note:** The refresh endpoint may rotate the `refreshToken`. Always store the latest returned value.

**Error Responses:**

| Status | Description | Action |
|--------|-------------|--------|
| 400 | Invalid or expired refresh token | Re-run Device Authorization Flow (Steps 1–3) |
| 401 | Refresh token revoked | Re-run Device Authorization Flow (Steps 1–3) |

---

## Polling Implementation

### Recommended Algorithm

```python
import time
import requests

def poll_for_credentials(device_code: str, interval: int, expires_in: int) -> dict:
    """
    Poll for credentials after user approves authorization.

    Args:
        device_code: Device code from authorization response
        interval: Polling interval in seconds
        expires_in: Seconds until expiration

    Returns:
        Credentials dict on success

    Raises:
        AuthorizationDenied: User denied
        AuthorizationExpired: Codes expired
        AuthorizationError: Other error
    """
    base_url = "https://dev.dfm.bitbi.io"
    end_time = time.time() + expires_in

    while time.time() < end_time:
        response = requests.post(
            f"{base_url}/api/v1/device/token",
            json={"deviceCode": device_code}
        )

        if response.status_code == 200:
            # Success! Return credentials
            return response.json()

        if response.status_code == 400:
            error = response.json()
            error_code = error.get("error")

            if error_code == "authorization_pending":
                # User hasn't acted yet - continue polling
                time.sleep(interval)
                continue

            if error_code == "access_denied":
                raise AuthorizationDenied("User denied the request")

            if error_code == "expired_token":
                raise AuthorizationExpired("Authorization expired")

            if error_code == "invalid_grant":
                raise AuthorizationError("Invalid device code")

            # Unknown error
            raise AuthorizationError(error.get("error_description", "Unknown error"))

        # Unexpected status code
        raise AuthorizationError(f"Unexpected status: {response.status_code}")

    raise AuthorizationExpired("Polling timeout")
```

### Key Points

1. **Respect `interval`**: Don't poll faster than specified (default: 5 seconds)
2. **Handle expiration**: Stop after `expiresIn` seconds
3. **Terminal errors**: Stop polling on `access_denied`, `expired_token`, `invalid_grant`
4. **Continue on**: `authorization_pending` - keep polling

---

## Complete Example (Python)

```python
import requests
import time

class DeviceAuthClient:
    def __init__(self, base_url: str = "https://dev.dfm.bitbi.io"):
        self.base_url = base_url
        self.credentials = None

    def authorize(self, site_name: str, site_description: str = None) -> dict:
        """Step 1: Initiate authorization."""
        response = requests.post(
            f"{self.base_url}/api/v1/device/authorize",
            json={
                "siteName": site_name,
                "siteDescription": site_description
            }
        )
        response.raise_for_status()
        return response.json()

    def display_code(self, auth_response: dict):
        """Step 2: Display code to user."""
        print("\n" + "=" * 40)
        print("    Device Authorization Required")
        print("=" * 40)
        print(f"\nOpen: {auth_response['verificationUri']}")
        print(f"\nEnter code: {auth_response['userCode']}")
        print(f"\nExpires in: {auth_response['expiresIn'] // 60} minutes")
        print("=" * 40 + "\n")

    def poll_for_credentials(self, device_code: str, interval: int, expires_in: int) -> dict:
        """Step 3: Poll for credentials."""
        end_time = time.time() + expires_in

        while time.time() < end_time:
            response = requests.post(
                f"{self.base_url}/api/v1/device/token",
                json={"deviceCode": device_code}
            )

            if response.status_code == 200:
                self.credentials = response.json()
                return self.credentials

            error = response.json()
            error_code = error.get("error")

            if error_code == "authorization_pending":
                print(".", end="", flush=True)
                time.sleep(interval)
                continue

            raise Exception(f"Authorization failed: {error_code}")

        raise Exception("Authorization timeout")

    def get_auth_header(self) -> str:
        """Get Bearer auth header for API requests."""
        if not self.credentials:
            raise Exception("Not authenticated")

        return f"Bearer {self.credentials['accessToken']}"

    def upload_file(self, file_path: str):
        """Step 4: Upload file using credentials."""
        headers = {
            "Authorization": self.get_auth_header()
        }

        # Initiate batch
        batch_response = requests.post(
            f"{self.credentials['apiBaseUrl']}/api/v1/device/batches",
            headers=headers
        )
        batch_response.raise_for_status()
        batch = batch_response.json()

        # Upload file
        with open(file_path, 'rb') as f:
            upload_response = requests.post(
                f"{self.credentials['apiBaseUrl']}/api/v1/device/batches/{batch['batchId']}/files",
                headers=headers,
                files={"file": f}
            )
            upload_response.raise_for_status()

        # Complete batch
        complete_response = requests.post(
            f"{self.credentials['apiBaseUrl']}/api/v1/device/batches/{batch['batchId']}/complete",
            headers=headers
        )
        complete_response.raise_for_status()

        print(f"Upload complete: {batch['batchId']}")


# Usage
if __name__ == "__main__":
    client = DeviceAuthClient("https://dev.dfm.bitbi.io")

    # Step 1: Initiate
    auth = client.authorize("my-device-01", "Production device")

    # Step 2: Display
    client.display_code(auth)

    # Step 3: Poll (blocking)
    print("Waiting for user approval", end="")
    credentials = client.poll_for_credentials(
        auth["deviceCode"],
        auth["interval"],
        auth["expiresIn"]
    )
    print("\nAuthorized!")

    # Step 4: Use credentials
    # client.upload_file("data.csv")

    # Store credentials for future use
    print(f"\nSite ID: {credentials['siteId']}")
    print(f"Site name: {credentials['siteName']}")
    print(f"Access token expires: {credentials['accessTokenExpiresAt']}")
    print(f"Refresh token (save securely): {credentials['refreshToken']}")
```

---

## Complete Example (Node.js)

```javascript
const axios = require('axios');

class DeviceAuthClient {
  constructor(baseUrl = 'https://dev.dfm.bitbi.io') {
    this.baseUrl = baseUrl;
    this.credentials = null;
  }

  async authorize(siteName, siteDescription = null) {
    const response = await axios.post(`${this.baseUrl}/api/v1/device/authorize`, {
      siteName,
      siteDescription
    });
    return response.data;
  }

  displayCode(authResponse) {
    console.log('\n========================================');
    console.log('    Device Authorization Required');
    console.log('========================================');
    console.log(`\nOpen: ${authResponse.verificationUri}`);
    console.log(`\nEnter code: ${authResponse.userCode}`);
    console.log(`\nExpires in: ${Math.floor(authResponse.expiresIn / 60)} minutes`);
    console.log('========================================\n');
  }

  async pollForCredentials(deviceCode, interval, expiresIn) {
    const endTime = Date.now() + (expiresIn * 1000);

    while (Date.now() < endTime) {
      try {
        const response = await axios.post(`${this.baseUrl}/api/v1/device/token`, {
          deviceCode
        });
        this.credentials = response.data;
        return this.credentials;
      } catch (error) {
        if (error.response?.status === 400) {
          const errorCode = error.response.data.error;

          if (errorCode === 'authorization_pending') {
            process.stdout.write('.');
            await this.sleep(interval * 1000);
            continue;
          }

          throw new Error(`Authorization failed: ${errorCode}`);
        }
        throw error;
      }
    }

    throw new Error('Authorization timeout');
  }

  getAuthHeader() {
    if (!this.credentials) throw new Error('Not authenticated');
    return `Bearer ${this.credentials.accessToken}`;
  }

  sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}

// Usage
async function main() {
  const client = new DeviceAuthClient('https://dev.dfm.bitbi.io');

  // Step 1: Initiate
  const auth = await client.authorize('my-device-01', 'Production device');

  // Step 2: Display
  client.displayCode(auth);

  // Step 3: Poll
  process.stdout.write('Waiting for user approval');
  const credentials = await client.pollForCredentials(
    auth.deviceCode,
    auth.interval,
    auth.expiresIn
  );
  console.log('\nAuthorized!');

  // Step 4: Use credentials
  console.log(`\nSite ID: ${credentials.siteId}`);
  console.log(`Site name: ${credentials.siteName}`);
  console.log(`Access token expires: ${credentials.accessTokenExpiresAt}`);
  console.log(`Refresh token (save securely): ${credentials.refreshToken}`);
}

main().catch(console.error);
```

---

## Complete Example (cURL)

```bash
#!/bin/bash

BASE_URL="https://dev.dfm.bitbi.io"

# Step 1: Initiate authorization
echo "Initiating device authorization..."
AUTH_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/device/authorize" \
  -H "Content-Type: application/json" \
  -d '{"siteName": "my-device-01", "siteDescription": "Test device"}')

DEVICE_CODE=$(echo $AUTH_RESPONSE | jq -r '.deviceCode')
USER_CODE=$(echo $AUTH_RESPONSE | jq -r '.userCode')
VERIFICATION_URI=$(echo $AUTH_RESPONSE | jq -r '.verificationUri')
INTERVAL=$(echo $AUTH_RESPONSE | jq -r '.interval')
EXPIRES_IN=$(echo $AUTH_RESPONSE | jq -r '.expiresIn')

# Step 2: Display code
echo ""
echo "========================================"
echo "    Device Authorization Required"
echo "========================================"
echo ""
echo "Open: $VERIFICATION_URI"
echo ""
echo "Enter code: $USER_CODE"
echo ""
echo "Expires in: $((EXPIRES_IN / 60)) minutes"
echo "========================================"
echo ""

# Step 3: Poll for credentials
echo "Waiting for user approval..."
END_TIME=$(($(date +%s) + EXPIRES_IN))

while [ $(date +%s) -lt $END_TIME ]; do
  RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/device/token" \
    -H "Content-Type: application/json" \
    -d "{\"deviceCode\": \"$DEVICE_CODE\"}")

  ERROR=$(echo $RESPONSE | jq -r '.error // empty')

  if [ -z "$ERROR" ]; then
    # Success!
    echo ""
    echo "Authorized!"
    echo ""
    echo "Site ID: $(echo $RESPONSE | jq -r '.siteId')"
    echo "Site name: $(echo $RESPONSE | jq -r '.siteName')"
    echo "Access token: $(echo $RESPONSE | jq -r '.accessToken')"
    echo "Access token expires: $(echo $RESPONSE | jq -r '.accessTokenExpiresAt')"
    echo "Refresh token (save securely): $(echo $RESPONSE | jq -r '.refreshToken')"
    exit 0
  fi

  if [ "$ERROR" = "authorization_pending" ]; then
    echo -n "."
    sleep $INTERVAL
    continue
  fi

  echo ""
  echo "Error: $ERROR"
  exit 1
done

echo ""
echo "Authorization timeout"
exit 1
```

---

## Error Handling

### HTTP Status Codes

| Status | Meaning |
|--------|---------|
| 200 | Success |
| 400 | Bad request / OAuth2 error |
| 401 | Authentication required (for protected endpoints) |
| 404 | Resource not found |
| 429 | Rate limit exceeded |
| 500 | Server error |

### OAuth2 Error Codes

| Code | Description |
|------|-------------|
| `invalid_request` | Malformed request |
| `authorization_pending` | User hasn't acted yet |
| `slow_down` | Polling too fast |
| `access_denied` | User denied |
| `expired_token` | Codes expired |
| `invalid_grant` | Invalid device code |

---

## Security Best Practices

1. **Store `refreshToken` securely** — the refresh token is the primary long-lived credential
2. **Keep `deviceCode` secret** - Only device should know it
3. **Use HTTPS** - All API calls must use HTTPS
4. **Implement credential storage** - Save `refreshToken` and `refreshTokenExpiresAt` for reuse
5. **Handle expiry** — if `refreshToken` expires, re-run the Device Authorization Flow

---

## Credential Persistence

After successful authorization, store the refresh token in your config file. For Rust CLI clients, use a `[auth]` section in `config.toml`:

```toml
[auth]
site_id = "550e8400-e29b-41d4-a716-446655440000"
site_name = "warehouse-01"
refresh_token = "dGhpcyBpcyBhIHJlZnJlc2g..."   # 43 chars, opaque
refresh_token_expires_at = "2026-05-22T10:35:00Z"
api_base_url = "https://dev.dfm.bitbi.io"
# access_token is not stored — obtain via refresh on each startup
```

**Startup logic:**
1. If `[auth]` section exists and `refresh_token_expires_at` is in the future → call `POST /api/v1/device/auth/refresh` to obtain a fresh `accessToken`, then skip Device Flow
2. Otherwise → run Device Authorization Flow (Steps 1–3) to get new tokens

---

## Troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| `invalid_request` on authorize | Invalid siteName | Check siteName format (alphanumeric + hyphens) |
| `authorization_pending` forever | User didn't approve | Check user received the code |
| `access_denied` | User clicked Deny | Restart authorization |
| `expired_token` | Took too long | Restart authorization (15 min limit) |
| `invalid_grant` | Wrong deviceCode | Check you're using correct code |
| Browser shows **Code Expired** | The submitted `userCode` was valid but its authorization window elapsed — on the lookup, or on Approve when the code ran out while the confirm card was open | Start a new authorization request on the device; the verification page distinguishes this from an unknown code |
| 401 on Batch API | Expired or invalid access token | Refresh via `POST /api/v1/device/auth/refresh` |
| A "Resource not found." toast on the verification page, over a flow that then succeeds | Fixed in #211: the page used to look a code up one keystroke before it was complete, and the 404 for that partial code reached the global error toast | Reload the page; on a build that carries #211 it cannot happen |
| `verificationUriComplete` opened with an empty code field | Fixed in #211: the login redirect dropped the query string | Retype the code; on a build that carries #211 the parameter survives |

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2025-01-12 | Initial release |
| 1.0.1 | 2026-08-19 | `verificationUriComplete` pre-fills the code again, and a partial code is no longer looked up (issue #211) |
| 1.0.2 | 2026-08-20 | Verification lookups distinguish an expired authorization from an unknown code; the browser presents the expired-code recovery action (issue #219) |

---

## Support

For questions or issues, contact the Data Forge team.
