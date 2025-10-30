package com.bitbi.dfm.account.domain;

import java.util.Objects;

/**
 * Company name Value Object with validation.
 * <p>
 * Enforces maximum length constraints for company names.
 * Immutable by design - any change requires creating a new instance.
 * </p>
 *
 * <h3>Validation Rules:</h3>
 * <ul>
 *   <li>Maximum length: 255 characters (database constraint)</li>
 *   <li>Minimum length: 2 characters (if provided)</li>
 *   <li>Null is allowed (optional field)</li>
 *   <li>Whitespace-only values are treated as null</li>
 * </ul>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class Company {

    private static final int MAX_LENGTH = 255;
    private static final int MIN_LENGTH = 2;

    private final String value;

    /**
     * Private constructor - use factory methods.
     */
    private Company(String value) {
        this.value = value;
    }

    /**
     * Create Company from string value with validation.
     *
     * @param value company name string (can be null or blank)
     * @return Company instance, or null if input is null/blank
     * @throws IllegalArgumentException if company name is invalid
     */
    public static Company of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        validate(trimmed);

        return new Company(trimmed);
    }

    /**
     * Validate company name length.
     *
     * @param company company name to validate
     * @throws IllegalArgumentException if validation fails
     */
    private static void validate(String company) {
        if (company.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Company name must be at least %d characters long: %d",
                            MIN_LENGTH, company.length())
            );
        }

        if (company.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Company name exceeds maximum length of %d characters: %d",
                            MAX_LENGTH, company.length())
            );
        }
    }

    /**
     * Get company name value.
     *
     * @return company name string
     */
    public String getValue() {
        return value;
    }

    /**
     * Check if company is empty/null.
     *
     * @return true if company is null
     */
    public boolean isEmpty() {
        return value == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Company company)) return false;
        return Objects.equals(value, company.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value != null ? value : "";
    }
}
