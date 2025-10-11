package com.bitbi.dfm.account.infrastructure;

import com.bitbi.dfm.account.domain.Company;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA AttributeConverter for Company Value Object.
 * <p>
 * Converts between Company domain object and String database column.
 * Automatically applied to all Company fields in entities.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Converter(autoApply = true)
public class CompanyConverter implements AttributeConverter<Company, String> {

    @Override
    public String convertToDatabaseColumn(Company company) {
        if (company == null) {
            return null;
        }
        return company.getValue();
    }

    @Override
    public Company convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        return Company.of(dbData);
    }
}
