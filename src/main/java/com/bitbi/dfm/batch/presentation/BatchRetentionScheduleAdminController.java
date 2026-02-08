package com.bitbi.dfm.batch.presentation;

import com.bitbi.dfm.batch.application.BatchRetentionScheduleService;
import com.bitbi.dfm.batch.application.BatchRetentionScheduleService.BatchRetentionSchedule;
import com.bitbi.dfm.batch.presentation.dto.BatchRetentionScheduleResponseDto;
import com.bitbi.dfm.batch.presentation.dto.UpdateBatchRetentionScheduleRequestDto;
import com.bitbi.dfm.shared.api.ApiRoutes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin controller for runtime scheduler settings (batch retention cleanup cron).
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "UI/Admin API - Settings", description = "Runtime-configurable settings for admins")
@SecurityRequirement(name = "oauth2")
public class BatchRetentionScheduleAdminController {

    private final BatchRetentionScheduleService scheduleService;

    public BatchRetentionScheduleAdminController(BatchRetentionScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Operation(summary = "Get batch retention cleanup scheduler schedule")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Schedule returned",
                    content = @Content(schema = @Schema(implementation = BatchRetentionScheduleResponseDto.class)))
    })
    @GetMapping(ApiRoutes.SETTINGS_BATCH_RETENTION_SCHEDULE)
    public ResponseEntity<BatchRetentionScheduleResponseDto> getSchedule() {
        BatchRetentionSchedule schedule = scheduleService.getSchedule();
        return ResponseEntity.ok(new BatchRetentionScheduleResponseDto(
                schedule.cron(),
                schedule.source().name(),
                schedule.updatedAt()
        ));
    }

    @Operation(summary = "Update batch retention cleanup scheduler cron")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Schedule updated",
                    content = @Content(schema = @Schema(implementation = BatchRetentionScheduleResponseDto.class)))
    })
    @PutMapping(ApiRoutes.SETTINGS_BATCH_RETENTION_SCHEDULE)
    public ResponseEntity<BatchRetentionScheduleResponseDto> updateSchedule(
            @Valid @RequestBody UpdateBatchRetentionScheduleRequestDto request) {
        BatchRetentionSchedule schedule = scheduleService.updateCron(request.cron());
        return ResponseEntity.ok(new BatchRetentionScheduleResponseDto(
                schedule.cron(),
                schedule.source().name(),
                schedule.updatedAt()
        ));
    }
}

