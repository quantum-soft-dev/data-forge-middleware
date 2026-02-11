# Auth V2 Migration Guide

## Overview

Auth V2 replaces Basic Auth credential-based authentication with a token-based system using JWT access tokens and refresh tokens. This is a **breaking change** that affects all clients using the Data Forge Middleware API.

**Key changes:**
- Basic Auth endpoints (`POST /api/v1/auth/token`, `POST /api/v1/device/auth/token`) are **removed**
- Device Flow now returns JWT + refresh token directly (instead of `clientSecret` + `compositeDomain`)
- Composite domain (`accountId_domain`) is **removed** from all API responses
- Site names now support unicode characters
- New `POST /api/v1/device/auth/refresh` endpoint for token renewal

---

## Migration Timeline

| Phase | Action |
|-------|--------|
| **Now** | Auth V2 endpoints available alongside deprecated V1 |
| **+30 days** | Basic Auth endpoints return 410 Gone |
| **+60 days** | Basic Auth endpoints removed |

---

## What's Removed

| Component | Status |
|-----------|--------|
| `POST /api/v1/auth/token` (Basic Auth) | **Removed** |
| `POST /api/v1/device/auth/token` (Basic Auth) | **Removed** |
| `clientSecret` in API responses | **Removed** |
| `siteIdentifier` (composite domain) in API responses | **Removed** |
| `domain` field in site responses | **Renamed to `siteName`** |

## What's New

| Component | Description |
|-----------|-------------|
| `POST /api/v1/device/auth/refresh` | Token refresh endpoint |
| `siteName` field | Replaces `domain` in all site responses |
| Refresh tokens | 90-day rotating tokens for session continuity |
| JWT access tokens (1h) | Short-lived tokens (previously 24h) |
| Unicode site names | No more alphanumeric-only restriction |

---

## New Authentication Flow

### Phase 1: Device Authorization (unchanged)

```
Device                              Server                              User
  |                                   |                                   |
  |-- POST /api/v1/device/authorize ->|                                   |
  |   {siteName, siteDescription}     |                                   |
  |                                   |                                   |
  |<- {deviceCode, userCode,       ---|                                   |
  |    verificationUri, expiresIn}    |                                   |
  |                                   |                                   |
  |   Display: "Go to {uri}"         |                                   |
  |   Display: "Enter code: XXXX-XXXX"|                                   |
  |                                   |                                   |
  |                                   |<-- User opens browser, logs in ---|
  |                                   |<-- User enters code, approves  ---|
```

### Phase 2: Token Retrieval (CHANGED)

```
Device                              Server
  |                                   |
  |-- POST /api/v1/device/token ----->|
  |   {deviceCode}  (polling 5s)      |
  |                                   |
  |<- {                            ---|
  |     siteId,                       |   (UUID)
  |     siteName,                     |   (display name)
  |     accessToken,                  |   (JWT, 1 hour TTL)
  |     refreshToken,                 |   (opaque, 90 days TTL)
  |     accessTokenExpiresAt,         |   (ISO 8601)
  |     refreshTokenExpiresAt,        |   (ISO 8601)
  |     apiBaseUrl                    |   (base URL for API calls)
  |   }                               |
```

**Old response (removed):**
```json
{
  "siteId": "...",
  "domain": "accountId_siteName",
  "clientSecret": "...",
  "apiBaseUrl": "..."
}
```

**New response:**
```json
{
  "siteId": "550e8400-e29b-41d4-a716-446655440000",
  "siteName": "warehouse-01",
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2g...",
  "accessTokenExpiresAt": "2025-02-10T15:30:00Z",
  "refreshTokenExpiresAt": "2025-05-11T14:30:00Z",
  "apiBaseUrl": "https://dev.dfm.bitbi.io"
}
```

### Phase 3: API Calls with Bearer JWT (unchanged)

```
Device                              Server
  |                                   |
  |-- POST /api/v1/device/batches/start -->|
  |   Authorization: Bearer {accessToken}  |
  |                                        |
  |<- {batchId, status}               ----|
```

### Phase 4: Token Refresh (NEW)

```
Device                              Server
  |                                   |
  |-- POST /api/v1/device/auth/refresh -->|
  |   {refreshToken}                      |
  |                                       |
  |<- {                               ---|
  |     accessToken,                      |   (new JWT, 1 hour)
  |     refreshToken,                     |   (rotated, new 90 days)
  |     accessTokenExpiresAt,             |
  |     refreshTokenExpiresAt             |
  |   }                                   |
```

---

## Endpoint Changes

| Old Endpoint | New Endpoint | Notes |
|-------------|-------------|-------|
| `POST /api/v1/auth/token` | **Removed** | Use Device Flow instead |
| `POST /api/v1/device/auth/token` | **Removed** | Use Device Flow instead |
| `POST /api/v1/device/token` | `POST /api/v1/device/token` | Response format changed (see above) |
| — | `POST /api/v1/device/auth/refresh` | **New**: Token refresh |

---

## Credential Persistence

### Old Model (remove)

```json
{
  "domain": "550e8400-..._warehouse-01",
  "clientSecret": "secret_abc123",
  "siteId": "550e8400-e29b-41d4-a716-446655440000",
  "apiBaseUrl": "https://dev.dfm.bitbi.io"
}
```

### New Model (store this)

```json
{
  "siteId": "550e8400-e29b-41d4-a716-446655440000",
  "siteName": "warehouse-01",
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2g...",
  "accessTokenExpiresAt": "2025-02-10T15:30:00Z",
  "refreshTokenExpiresAt": "2025-05-11T14:30:00Z",
  "apiBaseUrl": "https://dev.dfm.bitbi.io"
}
```

**Important:** Store both `accessToken` and `refreshToken` securely. The refresh token is a long-lived secret.

---

## Token Refresh Implementation

### Algorithm

```
Before each API call:
  1. Check if accessToken is expired (or will expire within 60 seconds)
  2. If expired:
     a. Call POST /api/v1/device/auth/refresh with refreshToken
     b. Store new accessToken and refreshToken
     c. Use new accessToken for the API call
  3. If refreshToken is expired:
     a. Re-run Device Authorization Flow from Step 1
```

### cURL

```bash
# Refresh tokens
curl -X POST https://dev.dfm.bitbi.io/api/v1/device/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "dGhpcyBpcyBhIHJlZnJlc2g..."}'
```

**Response:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "bmV3IHJlZnJlc2ggdG9rZW4...",
  "accessTokenExpiresAt": "2025-02-10T16:30:00Z",
  "refreshTokenExpiresAt": "2025-05-11T15:30:00Z"
}
```

### Python

```python
import requests
from datetime import datetime, timezone

class DataForgeClient:
    def __init__(self, config_path: str):
        self.config = self._load_config(config_path)
        self.base_url = self.config["apiBaseUrl"]

    def _is_token_expired(self) -> bool:
        expires_at = datetime.fromisoformat(
            self.config["accessTokenExpiresAt"].replace("Z", "+00:00")
        )
        # Refresh 60 seconds before expiry
        return datetime.now(timezone.utc) >= expires_at.replace(
            second=expires_at.second - 60
        )

    def _refresh_tokens(self):
        response = requests.post(
            f"{self.base_url}/api/v1/device/auth/refresh",
            json={"refreshToken": self.config["refreshToken"]}
        )

        if response.status_code == 401:
            error = response.json().get("error", "")
            if error in ("refresh_token_expired", "refresh_token_revoked"):
                raise Exception(f"Refresh token invalid: {error}. "
                              "Re-run Device Authorization Flow.")

        response.raise_for_status()
        data = response.json()

        self.config["accessToken"] = data["accessToken"]
        self.config["refreshToken"] = data["refreshToken"]
        self.config["accessTokenExpiresAt"] = data["accessTokenExpiresAt"]
        self.config["refreshTokenExpiresAt"] = data["refreshTokenExpiresAt"]
        self._save_config()

    def _get_headers(self) -> dict:
        if self._is_token_expired():
            self._refresh_tokens()
        return {"Authorization": f"Bearer {self.config['accessToken']}"}

    def start_batch(self):
        response = requests.post(
            f"{self.base_url}/api/v1/device/batches/start",
            headers=self._get_headers()
        )
        response.raise_for_status()
        return response.json()
```

### Node.js

```javascript
const axios = require('axios');

class DataForgeClient {
  constructor(config) {
    this.config = config;
    this.baseUrl = config.apiBaseUrl;
  }

  isTokenExpired() {
    const expiresAt = new Date(this.config.accessTokenExpiresAt);
    const now = new Date();
    // Refresh 60 seconds before expiry
    return now >= new Date(expiresAt.getTime() - 60000);
  }

  async refreshTokens() {
    try {
      const { data } = await axios.post(
        `${this.baseUrl}/api/v1/device/auth/refresh`,
        { refreshToken: this.config.refreshToken }
      );

      this.config.accessToken = data.accessToken;
      this.config.refreshToken = data.refreshToken;
      this.config.accessTokenExpiresAt = data.accessTokenExpiresAt;
      this.config.refreshTokenExpiresAt = data.refreshTokenExpiresAt;
      // Save config to persistent storage
    } catch (error) {
      if (error.response?.status === 401) {
        const err = error.response.data?.error;
        if (err === 'refresh_token_expired' || err === 'refresh_token_revoked') {
          throw new Error(`Refresh token invalid: ${err}. Re-run Device Authorization Flow.`);
        }
      }
      throw error;
    }
  }

  async getHeaders() {
    if (this.isTokenExpired()) {
      await this.refreshTokens();
    }
    return { Authorization: `Bearer ${this.config.accessToken}` };
  }

  async startBatch() {
    const headers = await this.getHeaders();
    const { data } = await axios.post(
      `${this.baseUrl}/api/v1/device/batches/start`,
      null,
      { headers }
    );
    return data;
  }
}
```

### Java

```java
import java.net.http.*;
import java.time.Instant;

public class DataForgeClient {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private Config config;

    private boolean isTokenExpired() {
        Instant expiresAt = Instant.parse(config.accessTokenExpiresAt);
        return Instant.now().isAfter(expiresAt.minusSeconds(60));
    }

    private void refreshTokens() throws Exception {
        String body = "{\"refreshToken\":\"" + config.refreshToken + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.apiBaseUrl + "/api/v1/device/auth/refresh"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401) {
            throw new RuntimeException(
                "Refresh token invalid. Re-run Device Authorization Flow.");
        }

        // Parse response and update config
        // config.accessToken = ...
        // config.refreshToken = ...
        // Save config to persistent storage
    }

    private String getAuthHeader() throws Exception {
        if (isTokenExpired()) {
            refreshTokens();
        }
        return "Bearer " + config.accessToken;
    }
}
```

---

## Error Handling

### Refresh Endpoint Errors

| HTTP Status | Error | Description | Action |
|-------------|-------|-------------|--------|
| 200 | — | Success | Store new tokens |
| 400 | `invalid_request` | Missing refreshToken | Check request body |
| 401 | `refresh_token_expired` | Refresh token TTL exceeded (90 days) | Re-run Device Authorization Flow |
| 401 | `refresh_token_revoked` | Token was revoked (e.g., site re-authorized) | Re-run Device Authorization Flow |
| 401 | `invalid_refresh_token` | Token not found or invalid | Re-run Device Authorization Flow |
| 500 | `internal_error` | Server error | Retry with exponential backoff |

### API Call Errors (Bearer JWT)

| HTTP Status | Meaning | Action |
|-------------|---------|--------|
| 401 | JWT expired or invalid | Refresh token, retry |
| 403 | Site doesn't own this resource | Check siteId |

---

## Migration Checklist

### For existing clients using Basic Auth:

- [ ] **Remove** stored `clientSecret` and `domain` (composite) from config
- [ ] **Remove** Basic Auth token generation code (`POST /api/v1/device/auth/token` or `POST /api/v1/auth/token`)
- [ ] **Run** Device Authorization Flow to get new JWT + refresh token
- [ ] **Store** `siteId`, `siteName`, `accessToken`, `refreshToken`, and expiry timestamps
- [ ] **Implement** automatic token refresh before API calls
- [ ] **Handle** refresh token expiry (re-run Device Authorization Flow)
- [ ] **Update** any references to `domain` field to use `siteName`
- [ ] **Test** full flow: authorize -> upload batch -> token refresh -> upload again

### For new clients:

- [ ] **Implement** Device Authorization Flow (Steps 1-2 from `device-flow-client-guide.md`)
- [ ] **Parse** new token response format from `POST /api/v1/device/token`
- [ ] **Store** tokens securely with expiry timestamps
- [ ] **Implement** automatic token refresh
- [ ] **Handle** error cases (expired refresh token, revoked token)

---

## FAQ

**Q: Can I still use Basic Auth?**
A: No. Basic Auth endpoints are removed in Auth V2. Use Device Flow with JWT + refresh tokens.

**Q: How long does the access token last?**
A: 1 hour. Use the refresh token to get a new access token without user interaction.

**Q: How long does the refresh token last?**
A: 90 days. After that, the user must re-authorize via Device Flow.

**Q: What happens when I refresh a token?**
A: The old refresh token is immediately revoked and a new one is issued (token rotation). Always store the new refresh token from the response.

**Q: What if the refresh token is lost?**
A: Re-run the Device Authorization Flow. The user will need to approve again.

**Q: Can a site have multiple active refresh tokens?**
A: No. When a new Device Authorization is approved for the same site, all previous refresh tokens are revoked.

**Q: Why was the composite domain removed?**
A: The `accountId_siteName` format was an internal implementation detail that leaked into the API. Site names now support unicode and are identified by the `(accountId, siteName)` pair internally.

**Q: Can site names contain special characters now?**
A: Yes. Site names support unicode characters (letters, numbers, dots, hyphens, spaces, etc.). The only restriction is 1-255 characters and non-blank.

---

## Breaking Changes Summary

| Change | Old | New |
|--------|-----|-----|
| Authentication | Basic Auth (`domain:clientSecret`) | Bearer JWT (1h) + refresh token (90d) |
| Token endpoint | `POST /api/v1/device/auth/token` | **Removed** (use Device Flow) |
| Token refresh | Not available (re-auth required) | `POST /api/v1/device/auth/refresh` |
| Device Flow response | `{siteId, domain, clientSecret, apiBaseUrl}` | `{siteId, siteName, accessToken, refreshToken, ...}` |
| Site identifier | `domain` (composite: `accountId_siteName`) | `siteName` (clean name) |
| Site name validation | `^[a-zA-Z0-9.-]+$` | Unicode allowed, 1-255 chars |
| JWT TTL | 24 hours | 1 hour |
| JWT claims | `siteId`, `accountId`, `domain` | `siteId`, `accountId` (no `domain`) |
| S3 paths (new batches) | `{accountId}/{compositeDomain}/{date}/{time}/` | `{accountId}/{siteId}/{date}/{time}/` |
