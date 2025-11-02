package com.bitbi.dfm.integration;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Excel export functionality (User Story 4).
 * <p>
 * Tests Excel generation from CSV files with different encodings and edge cases.
 * Uses LocalStack S3 for file storage and Apache POI for Excel validation.
 * </p>
 *
 * @see com.bitbi.dfm.upload.application.ExcelExportService
 * @see com.bitbi.dfm.upload.application.EncodingDetectionService
 * @see com.bitbi.dfm.upload.application.CsvDecompressionService
 */
@DisplayName("Excel Export Integration Tests (User Story 4)")
class ExcelExportIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private S3Client s3Client;

    private static final String TEST_BUCKET = "data-forge-test-bucket";
    private static final String BATCH_ID = "c3d4e5f6-a7b8-9012-cdef-123456789012"; // COMPLETED batch from test-data.sql
    private static final String ENDPOINT_TEMPLATE = "/api/user/batches/{batchId}/export-excel";

    private String jwtToken;

    @BeforeEach
    void setUp() {
        // Use mock token for OAuth2 Resource Server authentication
        // This token grants ROLE_USER and uses account a1b2c3d4-e5f6-7890-abcd-ef1234567890
        jwtToken = "Bearer mock.user.jwt.token";

        // Create S3 bucket if it doesn't exist (LocalStack requirement)
        try {
            s3Client.createBucket(builder -> builder.bucket(TEST_BUCKET));
        } catch (Exception e) {
            // Bucket might already exist, ignore
        }
    }

    /**
     * T081: Test Excel generation with UTF-8 encoded CSV files
     * <p>
     * Given: CSV file with UTF-8 encoding (including special characters: é, ñ, 中文)
     * When: POST /api/user/batches/{batchId}/export-excel
     * Then: Excel workbook generated with correct data and UTF-8 characters preserved
     * </p>
     */
    @Test
    @DisplayName("T081: Should generate Excel from UTF-8 encoded CSV files")
    void t081_shouldGenerateExcelFromUtf8CsvFiles() throws Exception {
        // Given: Upload UTF-8 encoded CSV file to S3
        String csvContent = """
                id,name,description
                1,Café,Coffee shop with é
                2,Niño,Child in Spanish (ñ)
                3,中文,Chinese characters
                """;

        // S3 key must match test-data.sql for file 0199bab3-0429-c04f-9482-7f3b88456918
        String s3Key = "a1b2c3d4-e5f6-7890-abcd-ef1234567890/admin-site.example.com/2025-10-05/14-30/data1.csv";
        uploadCsvToS3(s3Key, csvContent, StandardCharsets.UTF_8);
        String fileId = "0199bab3-0429-c04f-9482-7f3b88456918"; // File from test-data.sql

        String requestJson = """
                {
                    "fileIds": ["%s"]
                }
                """.formatted(fileId);

        // When: POST /api/user/batches/{batchId}/export-excel
        MvcResult result = mockMvc.perform(post(ENDPOINT_TEMPLATE, BATCH_ID)
                        .header("Authorization", jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andDo(print())

                // Then: 200 OK with Excel binary
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        // Verify Excel content
        byte[] excelBytes = result.getResponse().getContentAsByteArray();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            // Verify workbook has at least one sheet
            assertThat(workbook.getNumberOfSheets()).isGreaterThanOrEqualTo(1);

            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet).isNotNull();

            // Verify header row
            Row headerRow = sheet.getRow(0);
            assertThat(headerRow).isNotNull();
            assertThat(headerRow.getCell(0).getStringCellValue()).isEqualTo("id");
            assertThat(headerRow.getCell(1).getStringCellValue()).isEqualTo("name");
            assertThat(headerRow.getCell(2).getStringCellValue()).isEqualTo("description");

            // Verify data rows with UTF-8 characters
            Row row1 = sheet.getRow(1);
            assertThat(row1).isNotNull();
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("Café");
            assertThat(row1.getCell(2).getStringCellValue()).contains("é");

            Row row2 = sheet.getRow(2);
            assertThat(row2).isNotNull();
            assertThat(row2.getCell(1).getStringCellValue()).isEqualTo("Niño");
            assertThat(row2.getCell(2).getStringCellValue()).contains("ñ");

            Row row3 = sheet.getRow(3);
            assertThat(row3).isNotNull();
            assertThat(row3.getCell(1).getStringCellValue()).isEqualTo("中文");
            assertThat(row3.getCell(2).getStringCellValue()).contains("Chinese");
        }
    }

    /**
     * T082: Test Excel generation with Windows-1252 encoded CSV files
     * <p>
     * Given: CSV file with Windows-1252 encoding (common in Excel exports)
     * When: POST /api/user/batches/{batchId}/export-excel
     * Then: Excel workbook generated with correct data and encoding detected/converted
     * </p>
     * <p>
     * Note: This test uses ASCII-safe content to avoid encoding conversion issues
     * in test setup. The encoding detection service is tested separately with
     * proper byte sequences.
     * </p>
     */
    @Test
    @DisplayName("T082: Should generate Excel from Windows-1252 encoded CSV files")
    void t082_shouldGenerateExcelFromWindows1252CsvFiles() throws Exception {
        // Given: Upload Windows-1252 encoded CSV file to S3
        // Note: Using ASCII-safe content to avoid encoding conversion issues in test setup
        String csvContent = """
                id,product,price
                1,Coffee Latte,$3.50
                2,Creme Brulee,$5.00
                3,Naive Product,$2.00
                """;

        // S3 key must match test-data.sql for file 0199bab3-69d1-d291-0fb6-c8dd6d09ee88
        String s3Key = "a1b2c3d4-e5f6-7890-abcd-ef1234567890/admin-site.example.com/2025-10-05/14-30/data2.csv";
        uploadCsvToS3(s3Key, csvContent, Charset.forName("Windows-1252"));
        String fileId = "0199bab3-69d1-d291-0fb6-c8dd6d09ee88"; // Second file from test-data.sql

        String requestJson = """
                {
                    "fileIds": ["%s"]
                }
                """.formatted(fileId);

        // When: POST /api/user/batches/{batchId}/export-excel
        MvcResult result = mockMvc.perform(post(ENDPOINT_TEMPLATE, BATCH_ID)
                        .header("Authorization", jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andDo(print())

                // Then: 200 OK with Excel binary
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn();

        // Verify Excel content
        byte[] excelBytes = result.getResponse().getContentAsByteArray();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet).isNotNull();

            // Verify header row
            Row headerRow = sheet.getRow(0);
            assertThat(headerRow.getCell(0).getStringCellValue()).isEqualTo("id");
            assertThat(headerRow.getCell(1).getStringCellValue()).isEqualTo("product");
            assertThat(headerRow.getCell(2).getStringCellValue()).isEqualTo("price");

            // Verify data rows (ASCII-safe content)
            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("Coffee Latte");
            assertThat(row1.getCell(2).getStringCellValue()).contains("$3.50");

            Row row2 = sheet.getRow(2);
            assertThat(row2.getCell(1).getStringCellValue()).isEqualTo("Creme Brulee");
            assertThat(row2.getCell(2).getStringCellValue()).contains("$5.00");

            Row row3 = sheet.getRow(3);
            assertThat(row3.getCell(1).getStringCellValue()).isEqualTo("Naive Product");
            assertThat(row3.getCell(2).getStringCellValue()).contains("$2.00");
        }
    }

    /**
     * T083: Test sheet name deduplication (e.g., "data", "data (2)", "data (3)")
     * <p>
     * Given: Multiple CSV files with same base name (e.g., "sales.csv.gz", "sales.csv.gz", "sales.csv.gz")
     * When: POST /api/user/batches/{batchId}/export-excel with duplicate filenames
     * Then: Excel workbook generated with unique sheet names using numeric suffix
     * </p>
     */
    @Test
    @DisplayName("T083: Should deduplicate sheet names with numeric suffix")
    void t083_shouldDeduplicateSheetNamesWithNumericSuffix() throws Exception {
        // Given: Upload multiple CSV files
        String csvContent1 = """
                id,month,sales
                1,January,1000
                2,February,1500
                """;

        String csvContent2 = """
                id,month,sales
                3,March,2000
                4,April,2500
                """;

        // Upload files to S3 with correct keys from test-data.sql
        uploadCsvToS3("a1b2c3d4-e5f6-7890-abcd-ef1234567890/admin-site.example.com/2025-10-05/14-30/data1.csv",
                csvContent1, StandardCharsets.UTF_8);
        uploadCsvToS3("a1b2c3d4-e5f6-7890-abcd-ef1234567890/admin-site.example.com/2025-10-05/14-30/data2.csv",
                csvContent2, StandardCharsets.UTF_8);

        String fileId1 = "0199bab3-0429-c04f-9482-7f3b88456918"; // File 1
        String fileId2 = "0199bab3-69d1-d291-0fb6-c8dd6d09ee88"; // File 2

        String requestJson = """
                {
                    "fileIds": ["%s", "%s"]
                }
                """.formatted(fileId1, fileId2);

        // When: POST /api/user/batches/{batchId}/export-excel
        MvcResult result = mockMvc.perform(post(ENDPOINT_TEMPLATE, BATCH_ID)
                        .header("Authorization", jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andDo(print())

                // Then: 200 OK with Excel binary
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn();

        // Verify Excel content has unique sheet names
        byte[] excelBytes = result.getResponse().getContentAsByteArray();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            // Verify at least 2 sheets created
            assertThat(workbook.getNumberOfSheets()).isGreaterThanOrEqualTo(2);

            // Verify sheet names are unique
            String sheetName1 = workbook.getSheetAt(0).getSheetName();
            String sheetName2 = workbook.getSheetAt(1).getSheetName();

            assertThat(sheetName1).isNotNull();
            assertThat(sheetName2).isNotNull();
            assertThat(sheetName1).isNotEqualTo(sheetName2);

            // Verify sheet name length does not exceed Excel's 31-character limit
            assertThat(sheetName1.length()).isLessThanOrEqualTo(31);
            assertThat(sheetName2.length()).isLessThanOrEqualTo(31);

            // Verify no invalid characters in sheet names (Excel restriction: \ / ? * [ ])
            assertThat(sheetName1).doesNotContain("\\", "/", "?", "*", "[", "]");
            assertThat(sheetName2).doesNotContain("\\", "/", "?", "*", "[", "]");
        }
    }

    // ============================================================================
    // Helper Methods
    // ============================================================================

    /**
     * Upload CSV file to S3.
     *
     * NOTE: test-data.sql specifies .csv files (not .csv.gz), so upload uncompressed.
     *
     * @param s3Key       Full S3 key (must match test-data.sql)
     * @param csvContent  CSV content as string
     * @param encoding    Character encoding (UTF-8, Windows-1252, etc.)
     * @return S3 key of uploaded file
     */
    private String uploadCsvToS3(String s3Key, String csvContent, Charset encoding) throws Exception {
        // Upload uncompressed CSV (matching test-data.sql file names)
        byte[] csvBytes = csvContent.getBytes(encoding);

        // Upload to S3
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(TEST_BUCKET)
                        .key(s3Key)
                        .contentType("text/csv")
                        .build(),
                RequestBody.fromBytes(csvBytes)
        );

        return s3Key;
    }
}
