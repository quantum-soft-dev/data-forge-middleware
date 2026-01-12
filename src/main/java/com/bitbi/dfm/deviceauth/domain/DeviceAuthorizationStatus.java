package com.bitbi.dfm.deviceauth.domain;

/**
 * Status of a device authorization request.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public enum DeviceAuthorizationStatus {

    /**
     * Authorization request is pending user approval.
     */
    PENDING,

    /**
     * User approved the authorization, site created.
     */
    APPROVED,

    /**
     * User explicitly denied the authorization.
     */
    DENIED,

    /**
     * Authorization request expired before user action.
     */
    EXPIRED
}
