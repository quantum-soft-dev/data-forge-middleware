package com.bitbi.dfm.contract;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares a response body with a byte-level template of the JSON a client receives (issue #300).
 * <p>
 * A characterization of the HTTP surface has to pin what is on the wire, not what a JSON path
 * extracts from it: {@code jsonPath} ignores key order, cannot tell an explicit {@code null} from
 * an absent key without a dedicated matcher per field, and reads {@code 1} and {@code 1.0} as
 * equal. Jackson 3 changes exactly those defaults ({@code SORT_PROPERTIES_ALPHABETICALLY},
 * {@code WRITE_ENUMS_USING_TO_STRING}, date handling), so the template is the literal body with
 * only the values that differ per run replaced by placeholders:
 * </p>
 * <ul>
 *   <li>{@code <uuid>} — a canonical lower-case UUID;</li>
 *   <li>{@code <instant>} — an {@code Instant} as {@code Instant.toString()} writes it: UTC with a
 *       {@code Z} and 0, 3, 6 or 9 fraction digits;</li>
 *   <li>{@code <local-date-time>} — a {@code LocalDateTime} as ISO-8601 without an offset;</li>
 *   <li>{@code <string>} — any JSON string content (no quotes of its own).</li>
 * </ul>
 * Everything else is matched literally, so the template also pins whitespace (none) and the order
 * of keys.
 */
public final class WireJson {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("<(uuid|instant|local-date-time|string)>");

    private static final String UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String FRACTION = "(\\.\\d{3}|\\.\\d{6}|\\.\\d{9})?";
    private static final String INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}" + FRACTION + "Z";
    private static final String LOCAL_DATE_TIME = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}" + FRACTION + ")?";
    private static final String STRING = "[^\"\\\\]*";

    private WireJson() {
    }

    /**
     * @return whether {@code body} is exactly {@code template} with its placeholders filled in
     */
    public static boolean matches(String template, String body) {
        return toPattern(template).matcher(body).matches();
    }

    /**
     * Fails with both strings when the body does not match, so a red run shows the new wire form.
     */
    public static void assertMatches(String template, String body) {
        if (!matches(template, body)) {
            throw new AssertionError("Response body is not the pinned wire form.\n"
                    + "expected template: " + template + "\n"
                    + "actual body:       " + body);
        }
    }

    static Pattern toPattern(String template) {
        StringBuilder regex = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(template);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(template.substring(last, m.start())));
            regex.append(switch (m.group(1)) {
                case "uuid" -> UUID;
                case "instant" -> INSTANT;
                case "local-date-time" -> LOCAL_DATE_TIME;
                default -> STRING;
            });
            last = m.end();
        }
        regex.append(Pattern.quote(template.substring(last)));
        return Pattern.compile(regex.toString());
    }
}
