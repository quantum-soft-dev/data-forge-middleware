package com.bitbi.dfm.contract;

import com.bitbi.dfm.auth.application.RefreshTokenService;
import com.bitbi.dfm.integration.BaseIntegrationTest;
import com.bitbi.dfm.shared.api.ApiRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Characterization of the JSON bodies clients read, pinned on Spring Boot 3.5 / Jackson 2 before
 * the move to Jackson 3 (issue #300, the safety net for #302).
 * <p>
 * Each test pins the <em>whole</em> body through {@link WireJson}: key order (Jackson 3 sorts
 * properties alphabetically by default), explicit {@code null} members (the global
 * {@code JsonInclude.ALWAYS} of {@code JacksonConfiguration}, a Jackson 2 bean that Boot 4 no
 * longer uses as the HTTP mapper), {@code Instant} as an ISO string with {@code Z}, enum values as
 * their names, primitive names such as {@code isRead}, and integer vs. fractional numbers inside a
 * free-form map. The subjects are the surfaces read by clients this repository does not ship in
 * lockstep: the Device API (the Windows extractor), the application-wide error body, and the cursor
 * page the frontend's Zod schema parses. Bit BI and Parquet Export are pinned in their own contract
 * classes, which already mock their authentication.
 * </p>
 * The seeded rows come from {@code test-data.sql}; the class is {@code @Transactional}, so the
 * error log it writes is rolled back.
 */
@Transactional
@DisplayName("#300 — JSON response wire form (Boot 3.5 characterization)")
class JsonResponseWireContractTest extends BaseIntegrationTest {

    private static final String IN_PROGRESS_BATCH = "b1c2d3e4-f5a6-7890-bcde-f12345678903";
    private static final UUID STORE_03_SITE_ID = UUID.fromString("0199bab0-ca3b-e41c-5521-2f4b33fda8b6");

    @Autowired
    private RefreshTokenService refreshTokenService;

    private String body(RequestBuilder request, int expectedStatus) throws Exception {
        var response = mockMvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as("HTTP status").isEqualTo(expectedStatus);
        return response.getContentAsString();
    }

    @Test
    @DisplayName("Device API batch: declared key order, null completedAt written, Instant with Z")
    void deviceBatchInProgress() throws Exception {
        String body = body(get(ApiRoutes.DEVICE_BATCHES_GET, IN_PROGRESS_BATCH)
                .header("Authorization", generateToken("store-01.example.com")), 200);

        WireJson.assertMatches("{\"id\":\"" + IN_PROGRESS_BATCH + "\",\"batchId\":\"" + IN_PROGRESS_BATCH + "\","
                + "\"siteId\":\"0199baac-f852-753f-6fc3-7c994fc38654\",\"status\":\"IN_PROGRESS\","
                + "\"s3Path\":\"a1b2c3d4-e5f6-7890-abcd-ef1234567890/store-01.example.com/2025-10-06/12-00/\","
                + "\"uploadedFilesCount\":1,\"totalSize\":1024,\"hasErrors\":false,"
                + "\"startedAt\":\"<instant>\",\"completedAt\":null}", body);
    }

    @Test
    @DisplayName("Device API error log: enum by name, primitive isRead, free-form metadata numbers kept")
    void deviceErrorLog() throws Exception {
        String body = body(post(ApiRoutes.DEVICE_ERRORS_LOG)
                .header("Authorization", generateToken("store-01.example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"UPLOAD_FAILED\",\"message\":\"m\","
                        + "\"metadata\":{\"count\":1,\"big\":9007199254740993,\"ratio\":2.5,"
                        + "\"list\":[1,null,\"x\"],\"nested\":{\"ok\":true}}}"), 201);

        WireJson.assertMatches("{\"id\":\"<uuid>\",\"siteId\":\"0199baac-f852-753f-6fc3-7c994fc38654\","
                + "\"batchId\":null,\"type\":\"UPLOAD_FAILED\",\"title\":\"UPLOAD_FAILED\",\"message\":\"m\","
                + "\"stackTrace\":null,\"clientVersion\":null,"
                + "\"metadata\":{\"count\":1,\"big\":9007199254740993,\"ratio\":2.5,"
                + "\"list\":[1,null,\"x\"],\"nested\":{\"ok\":true}},"
                + "\"severity\":\"ERROR\",\"isRead\":false,\"occurredAt\":\"<instant>\"}", body);
    }

    @Test
    @DisplayName("ErrorResponseDto from GlobalExceptionHandler: timestamp, status, error, message, path")
    void applicationErrorBody() throws Exception {
        String badRequest = body(get(ApiRoutes.DEVICE_BATCHES_GET, "not-a-uuid")
                .header("Authorization", generateToken("store-01.example.com")), 400);
        WireJson.assertMatches("{\"timestamp\":\"<instant>\",\"status\":400,\"error\":\"Bad Request\","
                + "\"message\":\"Invalid value for parameter 'id': not-a-uuid\","
                + "\"path\":\"/api/v1/device/batches/not-a-uuid\"}", badRequest);

        String notFound = body(get("/api/v1/device/no-such-endpoint")
                .header("Authorization", generateToken("store-01.example.com")), 404);
        WireJson.assertMatches("{\"timestamp\":\"<instant>\",\"status\":404,\"error\":\"Not Found\","
                + "\"message\":\"Endpoint not found: /api/v1/device/no-such-endpoint\","
                + "\"path\":\"/api/v1/device/no-such-endpoint\"}", notFound);
    }

    @Test
    @DisplayName("Device flow token error: snake_case error_description, null interval omitted")
    void deviceTokenError() throws Exception {
        String body = body(post(ApiRoutes.DEVICE_AUTHORIZATION_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceCode\":\"no-such-device-code\"}"), 400);

        WireJson.assertMatches("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid device code\"}", body);
    }

    @Test
    @DisplayName("Device flow authorize: primitive ints expiresIn and interval")
    void deviceAuthorize() throws Exception {
        String body = body(post(ApiRoutes.DEVICE_AUTHORIZATION_AUTHORIZE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"siteName\":\"wire-characterization\"}"), 200);

        WireJson.assertMatches("{\"deviceCode\":\"<string>\",\"userCode\":\"<string>\","
                + "\"verificationUri\":\"<string>\",\"verificationUriComplete\":\"<string>\","
                + "\"expiresIn\":900,\"interval\":5}", body);
    }

    @Test
    @DisplayName("Device auth refresh: both expiries as Instant strings")
    void deviceRefresh() throws Exception {
        String refreshToken = refreshTokenService.generateRefreshToken(STORE_03_SITE_ID);

        String body = body(post(ApiRoutes.DEVICE_AUTH_REFRESH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"), 200);

        WireJson.assertMatches("{\"accessToken\":\"<string>\",\"refreshToken\":\"<string>\","
                + "\"accessTokenExpiresAt\":\"<instant>\",\"refreshTokenExpiresAt\":\"<instant>\"}", body);
    }

    @Test
    @DisplayName("Cursor page read by the frontend: nextCursor written as null on the last page")
    void cursorPageLastPage() throws Exception {
        String body = body(get(ApiRoutes.HISTORY_BATCHES)
                .param("limit", "100")
                .header("Authorization", "Bearer mock.user.jwt.token"), 200);

        assertThat(body).startsWith("{\"items\":[").endsWith("],\"nextCursor\":null,\"hasNext\":false}");
    }
}
