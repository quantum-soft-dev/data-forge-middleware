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
| Production | `https://api.dataforge.com` |
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
  "verificationUri": "https://app.dataforge.com/device-verify",
  "verificationUriComplete": "https://app.dataforge.com/device-verify?code=WDJB-MJHT",
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
  https://app.dataforge.com/device-verify

Enter this code:
  WDJB-MJHT

This code expires in 15 minutes.
===================================
```

**Tips:**
- Display `userCode` prominently
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

User has approved. Site created. Credentials returned:

```json
{
  "siteId": "550e8400-e29b-41d4-a716-446655440000",
  "domain": "c823d8b8-0e6f-4242-a350-d6ef335ab4e8_warehouse-01",
  "clientSecret": "dGhpcyBpcyBhIHNlY3JldCBrZXk=",
  "apiBaseUrl": "https://api.dataforge.com"
}
```

| Field | Description |
|-------|-------------|
| `siteId` | UUID of created site |
| `domain` | Domain for Basic Auth (username) |
| `clientSecret` | Secret for Basic Auth (password). **Returned only once!** |
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

Once you have credentials, use them for Batch API authentication:

### Authentication

Use **HTTP Basic Authentication**:
- **Username:** `domain` value
- **Password:** `clientSecret` value

```
Authorization: Basic base64(domain:clientSecret)
```

### Example: Initiate Batch Upload

```bash
curl -X POST "https://api.dataforge.com/api/v1/device/batches" \
  -u "c823d8b8-0e6f-4242-a350-d6ef335ab4e8_warehouse-01:dGhpcyBpcyBhIHNlY3JldCBrZXk=" \
  -H "Content-Type: application/json"
```

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
    base_url = "https://api.dataforge.com"
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
import base64

class DeviceAuthClient:
    def __init__(self, base_url: str = "https://api.dataforge.com"):
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
        """Get Basic Auth header for API requests."""
        if not self.credentials:
            raise Exception("Not authenticated")

        auth_string = f"{self.credentials['domain']}:{self.credentials['clientSecret']}"
        encoded = base64.b64encode(auth_string.encode()).decode()
        return f"Basic {encoded}"

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
    print(f"Domain: {credentials['domain']}")
    print(f"Secret: {credentials['clientSecret']} (save this!)")
```

---

## Complete Example (Node.js)

```javascript
const axios = require('axios');

class DeviceAuthClient {
  constructor(baseUrl = 'https://api.dataforge.com') {
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
    const auth = Buffer.from(
      `${this.credentials.domain}:${this.credentials.clientSecret}`
    ).toString('base64');
    return `Basic ${auth}`;
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
  console.log(`Domain: ${credentials.domain}`);
  console.log(`Secret: ${credentials.clientSecret} (save this!)`);
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
    echo "Domain: $(echo $RESPONSE | jq -r '.domain')"
    echo "Secret: $(echo $RESPONSE | jq -r '.clientSecret')"
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

1. **Store `clientSecret` securely** - It's returned only once!
2. **Keep `deviceCode` secret** - Only device should know it
3. **Use HTTPS** - All API calls must use HTTPS
4. **Implement credential storage** - Save credentials for reuse
5. **Handle secret rotation** - Re-authorize if secret is lost

---

## Credential Persistence

After successful authorization, store credentials securely:

```json
{
  "siteId": "550e8400-e29b-41d4-a716-446655440000",
  "domain": "c823d8b8-0e6f-4242-a350-d6ef335ab4e8_warehouse-01",
  "clientSecret": "dGhpcyBpcyBhIHNlY3JldCBrZXk=",
  "apiBaseUrl": "https://api.dataforge.com",
  "authorizedAt": "2025-01-12T10:30:00Z"
}
```

On next startup, check if credentials exist:
- **Yes**: Use stored credentials for Batch API
- **No**: Run Device Authorization Flow

---

## Troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| `invalid_request` on authorize | Invalid siteName | Check siteName format (alphanumeric + hyphens) |
| `authorization_pending` forever | User didn't approve | Check user received the code |
| `access_denied` | User clicked Deny | Restart authorization |
| `expired_token` | Took too long | Restart authorization (15 min limit) |
| `invalid_grant` | Wrong deviceCode | Check you're using correct code |
| 401 on Batch API | Wrong credentials | Verify domain and clientSecret |

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2025-01-12 | Initial release |

---

## Support

For questions or issues, contact the Data Forge team.
