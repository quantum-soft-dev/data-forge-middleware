# Authentication API Contract

**Base Path**: `/api/v1/auth`
**Authentication**: Basic Auth (initial) → JWT (subsequent)

---

## POST /api/v1/auth/token

**Summary**: Obtain JWT token using site credentials (domain:clientSecret)

**Request**:
- **Headers**:
  - `Authorization: Basic {base64(domain:clientSecret)}` (required)
  - `Content-Type: application/json`
- **Body**: None

**Responses**:

### 200 OK - Token issued successfully
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresIn": 86400,
  "tokenType": "Bearer"
}
```
**Schema**:
- `token` (string, required): JWT token string
- `expiresIn` (integer, required): Token lifetime in seconds (24 hours = 86400)
- `tokenType` (string, required): Always "Bearer"

### 401 Unauthorized - Invalid credentials or inactive site
```json
{
  "timestamp": "2025-10-06T10:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid credentials",
  "path": "/api/v1/auth/token"
}
```

**Business Rules**:
- Site must exist with matching domain
- clientSecret must match (constant-time comparison)
- Site must be active (isActive = true)
- Site's parent account must be active
- Token expires after 24 hours (configurable)

**JWT Payload**:
```json
{
  "sub": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "siteId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "accountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "domain": "store-01.example.com",
  "iat": 1696579200,
  "exp": 1696665600
}
```

**Contract Test** (`AuthContractTest.java`):
```java
@Test
void shouldIssueJwtTokenWhenValidCredentialsProvided() {
    // Given: Active site with valid credentials
    String credentials = Base64.getEncoder()
        .encodeToString("store-01.example.com:valid-secret-uuid".getBytes());

    // When: POST /api/v1/auth/token with Basic Auth
    mockMvc.perform(post("/api/v1/auth/token")
            .header("Authorization", "Basic " + credentials))

        // Then: 200 OK with JWT token structure
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").exists())
        .andExpect(jsonPath("$.expiresIn").value(86400))
        .andExpect(jsonPath("$.tokenType").value("Bearer"));
}

@Test
void shouldRejectAuthenticationWhenSiteInactive() {
    // Given: Inactive site
    String credentials = Base64.getEncoder()
        .encodeToString("inactive-site.com:secret".getBytes());

    // When: POST /api/v1/auth/token
    mockMvc.perform(post("/api/v1/auth/token")
            .header("Authorization", "Basic " + credentials))

        // Then: 401 Unauthorized
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Invalid credentials"));
}

@Test
void shouldRejectAuthenticationWhenInvalidSecret() {
    // Given: Valid domain with wrong secret
    String credentials = Base64.getEncoder()
        .encodeToString("store-01.example.com:wrong-secret".getBytes());

    // When: POST /api/v1/auth/token
    mockMvc.perform(post("/api/v1/auth/token")
            .header("Authorization", "Basic " + credentials))

        // Then: 401 Unauthorized
        .andExpect(status().isUnauthorized());
}
```

---

**OpenAPI Specification**:
```yaml
/api/v1/auth/token:
  post:
    summary: Obtain JWT token
    operationId: authenticateSite
    tags:
      - Authentication
    security:
      - basicAuth: []
    responses:
      '200':
        description: Token issued successfully
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/TokenResponse'
            example:
              token: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
              expiresIn: 86400
              tokenType: "Bearer"
      '401':
        $ref: '#/components/responses/Unauthorized'

components:
  schemas:
    TokenResponse:
      type: object
      required:
        - token
        - expiresIn
        - tokenType
      properties:
        token:
          type: string
          description: JWT token for subsequent API calls
        expiresIn:
          type: integer
          description: Token lifetime in seconds
          example: 86400
        tokenType:
          type: string
          description: Token type (always "Bearer")
          example: "Bearer"

  securitySchemes:
    basicAuth:
      type: http
      scheme: basic
      description: Site credentials (domain:clientSecret)

    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: JWT token obtained from /auth/token
```
