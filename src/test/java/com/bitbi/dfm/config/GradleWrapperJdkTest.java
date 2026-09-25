package com.bitbi.dfm.config;

import com.bitbi.dfm.testsupport.RunOwnedScratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wrapper must start on the JDK this repository compiles with (issue #352).
 *
 * <p>Gradle 9.0 runs on Java 17–24 only. Java 25 support begins at 9.1.0, and this
 * project has no older JDK: the toolchain is 25, and a machine with only that JDK
 * cannot launch 9.0 at all (IntelliJ reports every {@code oracle-25*} path as
 * incompatible). Spring Boot 4.1 accepts the whole Gradle 9.x line, so the pin is
 * the floor that actually runs, not a ceiling.</p>
 */
class GradleWrapperJdkTest {

    private static final Pattern DISTRIBUTION = Pattern.compile(
            "^distributionUrl=https\\\\://services\\.gradle\\.org/distributions/gradle-(\\d+)\\.(\\d+)(?:\\.(\\d+))?-bin\\.zip\\s*$",
            Pattern.MULTILINE);

    @Test
    @DisplayName("the wrapper is at least Gradle 9.1, so it runs on JDK 25")
    void wrapperRunsOnJdk25() throws IOException {
        String properties = Files.readString(RunOwnedScratch.projectRoot()
                .resolve("gradle/wrapper/gradle-wrapper.properties"));
        Matcher matcher = DISTRIBUTION.matcher(properties);
        assertThat(matcher.find())
                .as("distributionUrl must be the official bin zip, but was:%n%s", properties)
                .isTrue();
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        boolean runsOnJdk25 = major > 9 || (major == 9 && minor >= 1);
        assertThat(runsOnJdk25)
                .as("Gradle %s.%s cannot run on JDK 25 (needs 9.1+); IntelliJ then finds no compatible JDK",
                        matcher.group(1), matcher.group(2))
                .isTrue();
    }
}
