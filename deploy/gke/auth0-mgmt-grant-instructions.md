# Action required: authorize the forge M2M app for the Auth0 Management API

**Tenant:** `dev-dfm.us.auth0.com`
**Application (client_id):** `vlyrbKacW1kdcF5srxVYe99wN2UrmZKH`  _(this is the `DFM_M2M` client)_
**Resource server (audience):** `https://dev-dfm.us.auth0.com/api/v2/` (Auth0 Management API)

## Problem

The forge backend fetches an Auth0 **Management API** token at startup using this client's
`client_credentials`. Authentication succeeds, but the token request is rejected:

```
403 access_denied — Client "vlyrbKacW1kdcF5srxVYe99wN2UrmZKH" is not authorized to access
resource server "https://dev-dfm.us.auth0.com/api/v2/". You need to create a "client-grant".
```

The application is **not authorized** (no client-grant) for the Management API. We are **not**
asking to rotate or change the credentials — only to grant this existing client access.

## Fix — Option A: Dashboard (≈1 min)

1. Auth0 Dashboard → switch to tenant **dev-dfm**.
2. **Applications → APIs → Auth0 Management API** → tab **Machine to Machine Applications**.
3. Find the app with client_id `vlyrbKacW1kdcF5srxVYe99wN2UrmZKH` → toggle **Authorized = ON**.
4. Expand it (arrow) and enable the scopes below → **Update**.

## Fix — Option B: Auth0 CLI (one command)

```bash
auth0 login            # log in to the dev-dfm tenant
auth0 api post client-grants --data '{
  "client_id": "vlyrbKacW1kdcF5srxVYe99wN2UrmZKH",
  "audience": "https://dev-dfm.us.auth0.com/api/v2/",
  "scope": ["read:users","create:users","update:users","delete:users",
            "read:users_app_metadata","update:users_app_metadata",
            "read:roles","update:roles","create:role_members","read:role_members",
            "create:user_tickets"]
}'
```

## Required scopes

The backend uses the Management API for users, roles, and tickets (`mgmt.users()`,
`mgmt.roles()`, `mgmt.tickets()`):

```
read:users  create:users  update:users  delete:users
read:users_app_metadata  update:users_app_metadata
read:roles  update:roles  create:role_members  read:role_members
create:user_tickets
```

## How to verify (no app restart needed)

```bash
curl -s -X POST https://dev-dfm.us.auth0.com/oauth/token \
  -H 'content-type: application/json' \
  -d '{"client_id":"vlyrbKacW1kdcF5srxVYe99wN2UrmZKH",
       "client_secret":"<DFM_M2M_CLIENT_SECRET>",
       "audience":"https://dev-dfm.us.auth0.com/api/v2/",
       "grant_type":"client_credentials"}'
```

Expected: HTTP 200 with an `access_token` and a `scope` listing the grants above.
(Before the fix this returns `403 access_denied`.)
