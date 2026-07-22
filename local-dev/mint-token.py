#!/usr/bin/env python3
"""Mint a Delta Client v2 gRPC bearer token (HS256 JWT) for local testing.

The Delta gRPC auth interceptor validates the same custom HMAC-SHA256 JWT the
Client API v1 issues (com.bitbi.dfm.auth ...JwtTokenProvider): claims `siteId`,
`accountId`, `sub`, `iat`, `exp`, signed with `jwt.secret`. No Auth0 involved.

The site referenced by `siteId` must exist in the DB and be active
(see seed-delta-site.sql). Stdlib only — no PyJWT needed.

Usage:
  python3 local-dev/mint-token.py                # uses the seeded site defaults
  SITE_ID=... ACCOUNT_ID=... JWT_SECRET=... python3 local-dev/mint-token.py
"""
import base64
import hashlib
import hmac
import json
import os
import time

# Defaults match seed-delta-site.sql and the application.yml `jwt.secret` default.
SITE_ID = os.environ.get("SITE_ID", "0de17a00-0000-4000-8000-0000000000a1")
ACCOUNT_ID = os.environ.get("ACCOUNT_ID", "0de17a00-0000-4000-8000-000000000001")
SECRET = os.environ.get(
    "JWT_SECRET",
    # application-dev.yml -> jwt.secret (the dev profile's fixed HS256 secret).
    "dev-secret-key-minimum-256-bits-required-for-hmac-sha256-algorithm-development",
)
TTL_SECONDS = int(os.environ.get("TTL_SECONDS", "86400"))


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def main() -> None:
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "sub": SITE_ID,
        "siteId": SITE_ID,
        "accountId": ACCOUNT_ID,
        "iat": now,
        "exp": now + TTL_SECONDS,
    }
    signing_input = (
        b64url(json.dumps(header, separators=(",", ":")).encode())
        + "."
        + b64url(json.dumps(payload, separators=(",", ":")).encode())
    )
    signature = hmac.new(
        SECRET.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256
    ).digest()
    print(signing_input + "." + b64url(signature))


if __name__ == "__main__":
    main()
