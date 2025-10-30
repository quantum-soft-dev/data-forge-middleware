package com.bitbi.dfm.shared.presentation.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Generic wrapper for paginated responses.
 *
 * Maps Spring Data Page objects to a consistent DTO structure with pagination metadata.
 * Used for all paginated list endpoints (admin account/site listings, error log queries).
 *
 * @param <T> The DTO type for the page content
 */
public record PageResponseDto<T>(
    List<T> content,
    Integer page,
    Integer size,
    Long totalElements,
    Integer totalPages
) {

    /**
     * Creates a PageResponseDto from a Spring Data Page with entity-to-DTO conversion.
     *
     * @param page The Spring Data Page containing entities
     * @param mapper Function to convert entity to DTO
     * @param <E> The entity type
     * @param <T> The DTO type
     * @return PageResponseDto containing converted DTOs
     */
    public static <E, T> PageResponseDto<T> of(Page<E> page, Function<E, T> mapper) {
        List<T> content = page.getContent().stream()
            .map(mapper)
            .toList();

        return new PageResponseDto<>(
            content,
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages()
        );
    }
}
