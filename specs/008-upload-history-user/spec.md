# Feature Specification: Upload History

**Feature Branch**: `008-upload-history-user`
**Created**: 2025-11-01
**Status**: Draft
**Input**: User description: "# Upload history

## User Story
As a user, I want to see my upload history so that I can track their status and work with uploaded files.

## Functionality description

### Upload list
- Each completed upload is marked with a green tick
- Uploads with errors are marked with a red cross with the option to view error details

### Upload details
When opening an upload, the user sees:
- A list of all uploaded CSV files (.csv.gz format) with the size of each file indicated
- A 'Select all' checkbox for quickly selecting all files
- Individual checkboxes for selecting specific files

### File actions
- Download selected files in their original format (.csv.gz)
- Create an Excel file from the selected files, where each CSV becomes a separate sheet with a corresponding name

---

## Technical context
- Upload statistics are stored in the `batches` table (exists)
- File information is stored in the `uploaded_files` table (exists)"

## User Scenarios & Testing

### User Story 1 - View Upload History List (Priority: P1)

Users need to see an overview of all their upload sessions (batches) to quickly understand which uploads succeeded, which failed, and access details for further investigation or file downloads.

**Why this priority**: This is the entry point for the entire feature. Without the ability to view upload history, users cannot access any of the downstream functionality. It provides immediate value by giving users visibility into their upload activity.

**Independent Test**: Can be fully tested by authenticating as a user, navigating to the upload history page, and verifying that all upload sessions are displayed with correct status indicators. Delivers immediate value by providing upload visibility and status tracking.

**Acceptance Scenarios**:

1. **Given** user has completed 5 uploads (3 successful, 2 with errors), **When** user navigates to upload history page, **Then** system displays all 5 uploads sorted by date (newest first) with green tick for successful uploads and red cross for failed uploads
2. **Given** user has no upload history, **When** user navigates to upload history page, **Then** system displays empty state message "No uploads yet"
3. **Given** user has 100 uploads, **When** user views upload history, **Then** system displays paginated results with 20 uploads per page and pagination controls
4. **Given** user is viewing upload history list, **When** user sees an upload with red cross (errors), **Then** system displays "View errors" action button next to that upload
5. **Given** user is viewing upload history list, **When** upload is in progress, **Then** system displays progress indicator and prevents file actions until complete

---

### User Story 2 - View Upload Details and File List (Priority: P2)

Users need to drill into a specific upload session to see all files that were uploaded, their sizes, and select files for download or Excel generation.

**Why this priority**: This builds on P1 by providing detailed information about individual uploads. While users can see upload status in P1, they need this view to work with the actual files. It's independent of file download/Excel generation (P3) and delivers value through file visibility and metadata.

**Independent Test**: Can be fully tested by clicking on any upload from the history list and verifying that all uploaded files are displayed with correct names, sizes, and checkboxes. Delivers value by showing users exactly what files were uploaded in each session.

**Acceptance Scenarios**:

1. **Given** user clicks on a completed upload with 10 files, **When** upload details page loads, **Then** system displays all 10 CSV.gz files with individual checkboxes, file names, and file sizes in human-readable format (KB, MB)
2. **Given** user is viewing upload details with 5 files, **When** user clicks "Select all" checkbox, **Then** system selects all 5 file checkboxes
3. **Given** user has selected all files via "Select all", **When** user unchecks "Select all" checkbox, **Then** system deselects all file checkboxes
4. **Given** user has selected 3 out of 8 files individually, **When** user checks "Select all" checkbox, **Then** system selects remaining 5 files (all 8 now selected)
5. **Given** user is viewing upload details, **When** upload has no files (edge case: batch exists but upload failed before any files were saved), **Then** system displays "No files in this upload" message
6. **Given** user is viewing upload details for an upload with errors, **When** page loads, **Then** system displays error summary section with count of errors and link to error details

---

### User Story 3 - Download Selected Files (Priority: P3)

Users need to download their uploaded files in original format to archive them locally, share with colleagues, or verify upload integrity.

**Why this priority**: This is the primary file action. While viewing upload history and file lists (P1-P2) provides visibility, downloading files enables users to work with their data. It's more fundamental than Excel generation (P4) as it provides access to original data.

**Independent Test**: Can be fully tested by selecting files in upload details and clicking download button. Delivers value by enabling users to retrieve their original uploaded files without depending on Excel generation feature.

**Acceptance Scenarios**:

1. **Given** user has selected 3 files from upload details, **When** user clicks "Download selected" button, **Then** system downloads all 3 files packaged in a ZIP archive containing the original .csv.gz files
2. **Given** user has selected 1 file, **When** user clicks "Download selected" button, **Then** system downloads the single file directly (no ZIP wrapper)
3. **Given** user has not selected any files, **When** user attempts to click "Download selected" button, **Then** button is disabled/greyed out
4. **Given** user has selected 50 files totaling 500MB, **When** user clicks "Download selected", **Then** system initiates download and displays progress indicator
5. **Given** user is downloading files, **When** download fails due to network error, **Then** system displays error message with retry option

---

### User Story 4 - Generate Excel from Selected Files (Priority: P4)

Users need to consolidate multiple CSV files into a single Excel workbook for easier analysis, reporting, and sharing with stakeholders who prefer Excel format.

**Why this priority**: This is a convenience feature that adds significant value for analysis workflows but is not essential for basic file retrieval. Users can still download individual CSV files (P3) and manually combine them if needed. This feature enhances the user experience but can be delivered after core upload history functionality.

**Independent Test**: Can be fully tested by selecting CSV files and clicking "Create Excel" button, then verifying the downloaded Excel file has correct sheets and data. Delivers independent value by providing data consolidation without depending on other features.

**Acceptance Scenarios**:

1. **Given** user has selected 4 CSV.gz files, **When** user clicks "Create Excel" button, **Then** system generates Excel file with 4 sheets, each sheet named after corresponding CSV file (without .csv.gz extension)
2. **Given** user has selected files with names "sales-2024.csv.gz" and "inventory.csv.gz", **When** Excel is generated, **Then** Excel contains sheets named "sales-2024" and "inventory"
3. **Given** user has selected 1 file only, **When** user clicks "Create Excel", **Then** system generates Excel with single sheet containing that CSV's data
4. **Given** user has not selected any files, **When** user attempts to click "Create Excel" button, **Then** button is disabled/greyed out
5. **Given** user has selected files totaling 100MB of CSV data, **When** Excel generation is in progress, **Then** system displays progress indicator with estimated time remaining
6. **Given** Excel generation fails due to data format issues in one of the CSVs, **When** error occurs, **Then** system displays specific error message indicating which file caused the issue

---

### Edge Cases

- What happens when user tries to view upload details for a batch that was deleted or no longer exists (404 error handling)?
- How does system handle extremely large uploads with 1000+ files (pagination, performance, UI responsiveness)?
- What happens when user tries to download a file that has been archived or removed from S3 storage?
- How does system handle CSV files with special characters, very long names, or duplicate names when creating Excel sheets (sheet name character limit is 31)?
- What happens when user selects files totaling >2GB for Excel generation (Excel file size limits, memory constraints)?
- How does system handle concurrent download requests from the same user (rate limiting, queue management)?
- What happens when user navigates away while download or Excel generation is in progress (cancellation, cleanup)?
- How does system handle CSV files with different encodings (UTF-8, Windows-1252) when generating Excel?
- What happens when batch status is "IN_PROGRESS" or "EXPIRED" - should file actions be available?

## Requirements

### Functional Requirements

- **FR-001**: System MUST display list of all upload sessions (batches) for authenticated user sorted by creation date descending (newest first)
- **FR-002**: System MUST display visual status indicator for each upload: green checkmark for successful uploads (COMPLETED status, hasErrors=false), red cross for uploads with errors (COMPLETED status, hasErrors=true)
- **FR-003**: System MUST display "View errors" action for uploads marked with errors, linking to error details view
- **FR-004**: System MUST display pagination controls when user has more than 20 uploads, showing 20 uploads per page
- **FR-005**: System MUST display empty state message when user has no upload history
- **FR-006**: System MUST prevent file actions (download, Excel generation) for batches with status other than COMPLETED
- **FR-007**: System MUST display upload details showing all uploaded files from selected batch with file name, file size in human-readable format (KB, MB, GB), and selection checkbox
- **FR-008**: System MUST provide "Select all" checkbox that selects/deselects all files in current upload
- **FR-009**: System MUST enable "Download selected" button only when at least one file is selected
- **FR-010**: System MUST enable "Create Excel" button only when at least one file is selected
- **FR-011**: System MUST download single selected file directly in .csv.gz format, and package multiple selected files (2+) in a ZIP archive containing original .csv.gz files
- **FR-012**: System MUST generate Excel file where each selected CSV becomes a separate sheet named after the source file (removing .csv.gz extension)
- **FR-013**: System MUST handle Excel sheet name limitations by truncating sheet names to 31 characters maximum
- **FR-014**: System MUST decompress .csv.gz files before populating Excel sheets with CSV data
- **FR-015**: System MUST display progress indicator during file download and Excel generation operations
- **FR-016**: System MUST display error message with retry option when download or Excel generation fails
- **FR-017**: System MUST retrieve file data from S3 storage using s3Key stored in uploaded_files table
- **FR-018**: System MUST display total file count and total size for each upload in the history list
- **FR-019**: System MUST handle duplicate CSV file names when creating Excel by appending numeric suffix (e.g., "data", "data_2", "data_3")
- **FR-020**: System MUST restrict upload history and file access to files belonging to authenticated user's sites only (authorization check via batch.siteId → site.accountId)

### Key Entities

- **Batch (Upload Session)**: Represents a single upload session containing multiple files. Key attributes: unique identifier, site reference, status (IN_PROGRESS, COMPLETED, EXPIRED), error flag (hasErrors), start timestamp, completion timestamp, file count, total size.
- **Uploaded File**: Represents an individual file within an upload session. Key attributes: unique identifier, batch reference, original filename, S3 storage key, file size in bytes, checksum, upload timestamp. Relationship: belongs to one Batch.
- **Error Log**: Represents errors that occurred during upload. Key attributes: batch reference, error severity, error message, timestamp. Relationship: belongs to one Batch (used to determine hasErrors flag).

## Success Criteria

### Measurable Outcomes

- **SC-001**: Users can view their complete upload history within 2 seconds of page load for accounts with up to 1000 uploads
- **SC-002**: Users can navigate to upload details and see file list within 1 second for uploads containing up to 500 files
- **SC-003**: Users can download up to 10 selected files in under 5 seconds (excluding network transfer time)
- **SC-004**: Users can generate Excel file from up to 20 CSV files totaling 50MB in under 30 seconds
- **SC-005**: 95% of file downloads complete successfully on first attempt without requiring retry
- **SC-006**: System supports viewing upload history for accounts with 10,000+ historical uploads without performance degradation
- **SC-007**: Users can identify upload status (success/error) at a glance without reading detailed text (visual indicators)
- **SC-008**: Excel generation succeeds for CSV files with varying encodings (UTF-8, Windows-1252, ISO-8859-1)

## Assumptions

- Uploaded files are permanently stored in S3 and not archived or deleted (or if deleted, UI will handle 404 gracefully)
- CSV files are in valid format and can be parsed for Excel generation
- File sizes are reasonable (<100MB per file for Excel generation to avoid memory issues)
- Users primarily work with small to medium batches (1-100 files per upload)
- Excel format will be .xlsx (modern Excel format, not legacy .xls)
- File downloads will use browser's native download mechanism (no custom download manager)
- Pagination size of 20 uploads per page provides good balance between performance and usability
- Default CSV encoding is UTF-8 unless detected otherwise
- Download mechanism uses ZIP archive for multiple files (2+), direct download for single file to optimize user experience
- Users have modern browsers supporting HTML5 download attribute and JavaScript Blob/File APIs
- S3 file access does not require pre-signed URLs with expiration (or if required, URLs are generated on-demand server-side)

## Dependencies

- Existing `batches` table with columns: id, siteId, status, hasErrors, startedAt, completedAt, uploadedFilesCount, totalSize
- Existing `uploaded_files` table with columns: id, batchId, filename, s3Key, fileSize, checksum, uploadedAt
- Existing `error_logs` table for error details (referenced via batchId)
- S3 storage containing uploaded .csv.gz files accessible via s3Key
- Authentication system providing current user's account ID
- Authorization system verifying user can only access uploads from their own sites

## Out of Scope

- Editing or re-uploading files within existing batches
- Deleting individual files from completed uploads
- Sharing uploads or files with other users
- Advanced filtering or search within upload history (e.g., filter by date range, file name, status)
- Bulk operations across multiple uploads (e.g., download all files from last 7 days)
- File preview or inline viewing of CSV contents
- Custom Excel formatting, styles, or formulas
- Export to formats other than Excel (e.g., PDF, JSON)
- Email notifications for upload completion or errors
- Renaming files before download
- Retry logic for individual failed file uploads within a batch
- Upload history analytics or statistics dashboard
- Archive or cleanup of old uploads
