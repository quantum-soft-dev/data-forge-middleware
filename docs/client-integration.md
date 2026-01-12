# Data Forge Client Integration Guide

**Document Version**: 1.0.0
**Last Updated**: 2025-01-12
**Audience**: Data Forge Client developers

## Table of Contents

1. [Overview](#overview)
2. [Device Authorization Flow](#device-authorization-flow)
3. [API Reference](#api-reference)
4. [Client Configuration](#client-configuration)
5. [Error Handling](#error-handling)
6. [Code Examples](#code-examples)
7. [Troubleshooting](#troubleshooting)

---

## Overview

Data Forge Client uses **Device Authorization Flow** (based on RFC 8628) to securely obtain credentials without requiring users to manually copy-paste secrets.

### How It Works

1. **Client initiates authorization** with site name and description
2. **Client displays a code** for the user to enter in their browser
3. **User approves** the request in the Data Forge web interface
4. **Client receives credentials** automatically via polling
5. **Client saves credentials** to a local configuration file

### Key Benefits

- **No manual credential copying** — credentials are delivered automatically
- **Secure** — user explicitly approves each device
- **Headless-friendly** — works on devices without a browser

---

## Device Authorization Flow

### Sequence Diagram

```
┌──────────────┐                    ┌────────────────┐                    ┌─────────────┐
│   Client     │                    │  Data Forge    │                    │   Browser   │
│  (Device)    │                    │    API         │                    │   (User)    │
└──────┬───────┘                    └───────┬────────┘                    └──────┬──────┘
       │                                    │                                    │
       │ 1. POST /device/authorize          │                                    │
       │    {siteName, siteDescription}     │                                    │
       │───────────────────────────────────>│                                    │
       │                                    │                                    │
       │ 2. {deviceCode, userCode,          │                                    │
       │     verificationUri}               │                                    │
       │<───────────────────────────────────│                                    │
       │                                    │                                    │
       │ 3. Display to user:                │                                    │
       │    "Go to: /device-verify"         │                                    │
       │    "Enter code: WDJB-MJHT"         │                                    │
       │                                    │                                    │
       │                                    │ 4. User opens verification page    │
       │                                    │<───────────────────────────────────│
       │                                    │                                    │
       │                                    │ 5. User sees site info and clicks  │
       │                                    │    "Authorize & Create"            │
       │                                    │<───────────────────────────────────│
       │                                    │                                    │
       │                                    │    Site created automatically      │
       │                                    │                                    │
       │ 6. POST /device/token (polling)    │                                    │
       │───────────────────────────────────>│                                    │
       │                                    │                                    │
       │ 7. {siteId, domain, clientSecret}  │                                    │
       │<───────────────────────────────────│                                    │
       │                                    │                                    │
       │ 8. Save credentials to config file │                                    │
       │    Client ready to work!           │                                    │
```

### Step-by-Step

| Step | Actor | Action |
|------|-------|--------|
| 1 | Client | Calls `POST /api/v1/device/authorize` with `siteName` and `siteDescription` |
| 2 | API | Returns `deviceCode`, `userCode`, and `verificationUri` |
| 3 | Client | Displays instructions: "Go to {verificationUri} and enter code: {userCode}" |
| 4 | User | Opens the verification URL in a browser and logs in |
| 5 | User | Reviews site info and clicks "Authorize & Create" |
| 6 | Client | Polls `POST /api/v1/device/token` every 5 seconds |
| 7 | API | Returns credentials after user approval |
| 8 | Client | Saves credentials to configuration file |

---

## API Reference

### Base URL

| Environment | URL |
|-------------|-----|
| Production | `https://api.dataforge.com` |
| Staging | `https://api.staging.dataforge.com` |
| Development | `http://localhost:8080` |

---

### 1. Initiate Device Authorization

**Endpoint**: `POST /api/v1/device/authorize`
**Authentication**: None (public endpoint)

Initiates the device authorization flow by providing site information.

#### Request

```http
POST /api/v1/device/authorize HTTP/1.1
Host: api.dataforge.com
Content-Type: application/json

{
  "siteName": "warehouse-01",
  "siteDescription": "Main warehouse terminal"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `siteName` | string | Yes | Unique site identifier (max 100 chars, alphanumeric + hyphens) |
| `siteDescription` | string | No | Human-readable description (max 500 chars) |

#### Response (200 OK)

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

| Field | Type | Description |
|-------|------|-------------|
| `deviceCode` | string | Secret code for polling (keep private) |
| `userCode` | string | Code to display to user (format: XXXX-XXXX) |
| `verificationUri` | string | URL where user should go |
| `verificationUriComplete` | string | URL with code pre-filled |
| `expiresIn` | integer | Seconds until codes expire (default: 900 = 15 min) |
| `interval` | integer | Minimum seconds between polling requests |

#### Error Responses

| Status | Error | Description |
|--------|-------|-------------|
| 400 | `invalid_request` | Missing or invalid siteName |
| 429 | `too_many_requests` | Rate limit exceeded |

---

### 2. Poll for Token

**Endpoint**: `POST /api/v1/device/token`
**Authentication**: None (public endpoint)

Poll this endpoint to check if the user has approved the authorization.

#### Request

```http
POST /api/v1/device/token HTTP/1.1
Host: api.dataforge.com
Content-Type: application/json

{
  "deviceCode": "GmRhmhcxhwAzkoEqiMEg_DnyEysNkuNhszIySk9eS"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `deviceCode` | string | Yes | Device code from authorization response |

#### Response: Authorization Pending (400)

```json
{
  "error": "authorization_pending",
  "error_description": "The user has not yet completed authorization"
}
```

**Action**: Continue polling at the specified interval.

#### Response: Slow Down (400)

```json
{
  "error": "slow_down",
  "error_description": "Polling too frequently",
  "interval": 10
}
```

**Action**: Increase polling interval to the specified value.

#### Response: Success (200)

```json
{
  "siteId": "550e8400-e29b-41d4-a716-446655440000",
  "domain": "acct123_warehouse-01",
  "clientSecret": "dGhpcyBpcyBhIHNlY3JldCBrZXk=",
  "apiBaseUrl": "https://api.dataforge.com"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `siteId` | UUID | Unique site identifier |
| `domain` | string | Full domain for authentication (format: `{accountId}_{siteName}`) |
| `clientSecret` | string | Secret for Basic Auth (store securely!) |
| `apiBaseUrl` | string | API base URL for subsequent requests |

#### Response: Denied (400)

```json
{
  "error": "access_denied",
  "error_description": "The user denied the authorization request"
}
```

**Action**: Stop polling. User must restart the flow.

#### Response: Expired (400)

```json
{
  "error": "expired_token",
  "error_description": "The device code has expired"
}
```

**Action**: Stop polling. Restart the authorization flow.

---

## Client Configuration

After successful authorization, save credentials to a configuration file:

### Configuration File Location

| Platform | Path |
|----------|------|
| Linux | `~/.config/dataforge/config.json` |
| macOS | `~/Library/Application Support/dataforge/config.json` |
| Windows | `%APPDATA%\dataforge\config.json` |

### Configuration File Format

```json
{
  "siteId": "550e8400-e29b-41d4-a716-446655440000",
  "domain": "acct123_warehouse-01",
  "clientSecret": "dGhpcyBpcyBhIHNlY3JldCBrZXk=",
  "apiBaseUrl": "https://api.dataforge.com",
  "createdAt": "2025-01-12T15:30:00Z"
}
```

### Security Recommendations

1. **File permissions**: Set restrictive permissions (e.g., `chmod 600` on Unix)
2. **Never log secrets**: Do not log `clientSecret` or `deviceCode`
3. **Secure storage**: Consider using OS keychain/credential manager for production

---

## Error Handling

### Error Response Format

All errors follow this format:

```json
{
  "error": "error_code",
  "error_description": "Human-readable description"
}
```

### Error Codes

| Error Code | HTTP Status | Description | Action |
|------------|-------------|-------------|--------|
| `invalid_request` | 400 | Missing or malformed parameters | Check request body |
| `authorization_pending` | 400 | User hasn't approved yet | Continue polling |
| `slow_down` | 400 | Polling too frequently | Increase interval |
| `access_denied` | 400 | User denied request | Stop, restart flow |
| `expired_token` | 400 | Codes expired | Stop, restart flow |
| `invalid_grant` | 400 | Invalid device code | Stop, restart flow |
| `server_error` | 500 | Internal server error | Retry with backoff |

### Recommended Retry Strategy

```python
import time

def poll_with_backoff(device_code, initial_interval=5, max_attempts=180):
    interval = initial_interval

    for attempt in range(max_attempts):
        response = poll_token(device_code)

        if response.status_code == 200:
            return response.json()  # Success!

        error = response.json().get("error")

        if error == "authorization_pending":
            time.sleep(interval)
            continue

        if error == "slow_down":
            interval = response.json().get("interval", interval + 5)
            time.sleep(interval)
            continue

        if error in ["access_denied", "expired_token", "invalid_grant"]:
            raise AuthorizationError(error)

        # Server error - retry with exponential backoff
        time.sleep(min(interval * 2, 60))

    raise TimeoutError("Authorization timed out")
```

---

## Code Examples

### Python Example

```python
import requests
import time
import json
from pathlib import Path

API_BASE = "https://api.dataforge.com"

def authorize_device(site_name: str, site_description: str = None) -> dict:
    """
    Complete device authorization flow.
    Returns credentials on success.
    """
    # Step 1: Initiate authorization
    response = requests.post(
        f"{API_BASE}/api/v1/device/authorize",
        json={
            "siteName": site_name,
            "siteDescription": site_description
        }
    )
    response.raise_for_status()
    auth_data = response.json()

    # Step 2: Display instructions to user
    print("\n" + "="*50)
    print("DEVICE AUTHORIZATION REQUIRED")
    print("="*50)
    print(f"\n1. Open: {auth_data['verificationUri']}")
    print(f"2. Enter code: {auth_data['userCode']}")
    print(f"\nOr open directly: {auth_data['verificationUriComplete']}")
    print(f"\nThis code expires in {auth_data['expiresIn'] // 60} minutes.")
    print("\nWaiting for authorization...")

    # Step 3: Poll for token
    device_code = auth_data["deviceCode"]
    interval = auth_data["interval"]
    expires_at = time.time() + auth_data["expiresIn"]

    while time.time() < expires_at:
        time.sleep(interval)

        response = requests.post(
            f"{API_BASE}/api/v1/device/token",
            json={"deviceCode": device_code}
        )

        if response.status_code == 200:
            credentials = response.json()
            print("\n✓ Authorization successful!")
            return credentials

        error_data = response.json()
        error = error_data.get("error")

        if error == "authorization_pending":
            print(".", end="", flush=True)
            continue

        if error == "slow_down":
            interval = error_data.get("interval", interval + 5)
            continue

        if error == "access_denied":
            raise Exception("Authorization denied by user")

        if error == "expired_token":
            raise Exception("Authorization expired")

        raise Exception(f"Unexpected error: {error}")

    raise Exception("Authorization timed out")


def save_credentials(credentials: dict):
    """Save credentials to config file."""
    config_dir = Path.home() / ".config" / "dataforge"
    config_dir.mkdir(parents=True, exist_ok=True)

    config_file = config_dir / "config.json"
    config_file.write_text(json.dumps(credentials, indent=2))
    config_file.chmod(0o600)  # Restrict permissions

    print(f"Credentials saved to: {config_file}")


def main():
    # Example usage
    credentials = authorize_device(
        site_name="warehouse-01",
        site_description="Main warehouse terminal"
    )

    save_credentials(credentials)

    print(f"\nSite ID: {credentials['siteId']}")
    print(f"Domain: {credentials['domain']}")
    print("Ready to start batch uploads!")


if __name__ == "__main__":
    main()
```

### cURL Example

```bash
#!/bin/bash

API_BASE="https://api.dataforge.com"

# Step 1: Initiate authorization
echo "Initiating device authorization..."
AUTH_RESPONSE=$(curl -s -X POST "$API_BASE/api/v1/device/authorize" \
  -H "Content-Type: application/json" \
  -d '{
    "siteName": "warehouse-01",
    "siteDescription": "Main warehouse terminal"
  }')

DEVICE_CODE=$(echo $AUTH_RESPONSE | jq -r '.deviceCode')
USER_CODE=$(echo $AUTH_RESPONSE | jq -r '.userCode')
VERIFICATION_URI=$(echo $AUTH_RESPONSE | jq -r '.verificationUri')
INTERVAL=$(echo $AUTH_RESPONSE | jq -r '.interval')

echo ""
echo "========================================"
echo "DEVICE AUTHORIZATION REQUIRED"
echo "========================================"
echo ""
echo "1. Open: $VERIFICATION_URI"
echo "2. Enter code: $USER_CODE"
echo ""
echo "Waiting for authorization..."

# Step 2: Poll for token
while true; do
  sleep $INTERVAL

  TOKEN_RESPONSE=$(curl -s -X POST "$API_BASE/api/v1/device/token" \
    -H "Content-Type: application/json" \
    -d "{\"deviceCode\": \"$DEVICE_CODE\"}")

  ERROR=$(echo $TOKEN_RESPONSE | jq -r '.error // empty')

  if [ -z "$ERROR" ]; then
    echo ""
    echo "✓ Authorization successful!"
    echo ""
    echo "Credentials:"
    echo $TOKEN_RESPONSE | jq .

    # Save to config file
    mkdir -p ~/.config/dataforge
    echo $TOKEN_RESPONSE > ~/.config/dataforge/config.json
    chmod 600 ~/.config/dataforge/config.json

    echo ""
    echo "Saved to ~/.config/dataforge/config.json"
    break
  fi

  case $ERROR in
    "authorization_pending")
      echo -n "."
      ;;
    "slow_down")
      INTERVAL=$(echo $TOKEN_RESPONSE | jq -r '.interval')
      ;;
    "access_denied")
      echo ""
      echo "✗ Authorization denied by user"
      exit 1
      ;;
    "expired_token")
      echo ""
      echo "✗ Authorization expired"
      exit 1
      ;;
    *)
      echo ""
      echo "✗ Error: $ERROR"
      exit 1
      ;;
  esac
done
```

---

## Troubleshooting

### Common Issues

#### "invalid_request" when calling /device/authorize

**Cause**: Invalid `siteName` format.

**Solution**: Ensure `siteName`:
- Is not empty
- Contains only alphanumeric characters and hyphens
- Is 100 characters or less
- Does not start or end with a hyphen

#### "authorization_pending" never resolves

**Possible causes**:
1. User hasn't opened the verification URL
2. User entered wrong code
3. User is not logged in

**Solution**:
- Verify the `verificationUriComplete` URL works
- Check user is logged into Data Forge
- Ensure user is entering the exact code displayed

#### "expired_token" error

**Cause**: User took too long (> 15 minutes) to approve.

**Solution**: Restart the authorization flow by calling `/device/authorize` again.

#### "access_denied" error

**Cause**: User explicitly denied the authorization request.

**Solution**:
- Contact the user to understand why
- Restart the flow if it was accidental

#### Credentials not working for API calls

**Cause**: Using credentials incorrectly.

**Solution**: Use Basic Authentication with:
- Username: `domain` value from credentials
- Password: `clientSecret` value from credentials

Example:
```bash
curl -X POST "$API_BASE/api/dfc/auth/token" \
  -u "acct123_warehouse-01:your-client-secret"
```

---

## Related Documentation

- [Batch Upload API](./batch-upload-api.md) — How to upload batches after authorization
- [Error Reporting API](./error-reporting-api.md) — How to report errors
- [Bit BI Integration](./bitbi-integration.md) — Plugin integration guide

---

## Changelog

### v1.0.0 (2025-01-12)
- Initial release
- Device Authorization Flow documentation
- Python and cURL examples
