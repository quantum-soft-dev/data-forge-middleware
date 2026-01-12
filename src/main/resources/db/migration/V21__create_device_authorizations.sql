-- Device Authorization Flow (RFC 8628 based)
-- Stores pending device authorization requests with site creation data

CREATE TABLE device_authorizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Authorization codes
    device_code VARCHAR(64) NOT NULL UNIQUE,
    user_code VARCHAR(10) NOT NULL UNIQUE,

    -- Site creation data (replaces deviceName/deviceType)
    site_name VARCHAR(100) NOT NULL,
    site_description VARCHAR(500),

    -- Result after approval (populated on approve)
    account_id UUID REFERENCES accounts(id),
    site_id UUID REFERENCES sites(id),
    domain VARCHAR(200),
    client_secret_hash VARCHAR(200),

    -- Status tracking
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- Timestamps
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    approved_at TIMESTAMPTZ,

    -- Constraints
    CONSTRAINT chk_device_auth_status CHECK (status IN ('PENDING', 'APPROVED', 'DENIED', 'EXPIRED'))
);

-- Indexes for lookup operations
CREATE INDEX idx_device_auth_device_code ON device_authorizations(device_code);
CREATE INDEX idx_device_auth_user_code ON device_authorizations(user_code);
CREATE INDEX idx_device_auth_status_expires ON device_authorizations(status, expires_at)
    WHERE status = 'PENDING';

-- Index for cleanup job
CREATE INDEX idx_device_auth_expires_at ON device_authorizations(expires_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE device_authorizations IS 'Device authorization requests for headless client registration';
COMMENT ON COLUMN device_authorizations.device_code IS 'Secret code used by device for polling (64 chars)';
COMMENT ON COLUMN device_authorizations.user_code IS 'Human-readable code for user to enter (format: XXXX-XXXX)';
COMMENT ON COLUMN device_authorizations.site_name IS 'Requested site name (will be created on approval)';
COMMENT ON COLUMN device_authorizations.client_secret_hash IS 'BCrypt hash of generated client secret (stored on approval)';
