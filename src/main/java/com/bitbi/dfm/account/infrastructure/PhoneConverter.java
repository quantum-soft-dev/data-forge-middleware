package com.bitbi.dfm.account.infrastructure;

import com.bitbi.dfm.account.domain.Phone;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA AttributeConverter for Phone Value Object.
 * <p>
 * Converts between Phone domain object and String database column.
 * Automatically applied to all Phone fields in entities.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Converter(autoApply = true)
public class PhoneConverter implements AttributeConverter<Phone, String> {

    @Override
    public String convertToDatabaseColumn(Phone phone) {
        if (phone == null) {
            return null;
        }
        return phone.getValue();
    }

    @Override
    public Phone convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        return Phone.of(dbData);
    }
}
