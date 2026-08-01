package com.bitbi.dfm.batch.presentation;

import com.bitbi.dfm.batch.application.BatchDeletionService;
import com.bitbi.dfm.batch.application.BatchLifecycleService;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The admin controller delegates batch deletion to its application boundary. */
class BatchAdminControllerDeleteTest {

    private final BatchRepository batchRepository = mock(BatchRepository.class);
    private final BatchDeletionService batchDeletionService = mock(BatchDeletionService.class);
    private final BatchAdminController controller = new BatchAdminController(batchRepository,
            mock(SiteRepository.class), mock(UploadedFileRepository.class),
            mock(BatchLifecycleService.class), batchDeletionService);

    @Test
    void delegatesDeletionAndAnswersNoContent() {
        UUID batchId = UUID.randomUUID();
        when(batchDeletionService.deleteBatch(batchId)).thenReturn(true);

        assertEquals(HttpStatus.NO_CONTENT, controller.deleteBatch(batchId).getStatusCode());

        verify(batchDeletionService).deleteBatch(batchId);
    }

    @Test
    void answers404WhenTheApplicationServiceDoesNotFindTheBatch() {
        UUID batchId = UUID.randomUUID();
        when(batchDeletionService.deleteBatch(batchId)).thenReturn(false);

        assertEquals(HttpStatus.NOT_FOUND, controller.deleteBatch(batchId).getStatusCode());
    }
}
