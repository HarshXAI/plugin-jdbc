package io.kestra.plugin.jdbc.duckdb;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.kestra.core.models.tasks.common.FetchType.FETCH_ONE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class DuckDbBundledExtensionTest {
    private static final Pattern DUCKDB_VERSION = Pattern.compile("v?(\\d+\\.\\d+\\.\\d+)");

    @Inject
    private RunContextFactory runContextFactory;

    private final List<ILoggingEvent> logs = new CopyOnWriteArrayList<>();
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logs.clear();
        appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                logs.add(event);
            }
        };
        appender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
    }

    @Test
    void bundledExtensionVersionMatchesDriver() throws Exception {
        Properties properties = new Properties();
        try (InputStream inputStream = DuckDbBundledExtensionTest.class.getClassLoader()
            .getResourceAsStream("duckdb-community-extensions.properties")) {
            assertThat(inputStream, notNullValue());
            properties.load(inputStream);
        }

        String bundledVersion = properties.getProperty("duckdb.version");
        assertThat(bundledVersion, notNullValue());

        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            String runtimeVersion;
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT version()")) {
                resultSet.next();
                runtimeVersion = resultSet.getString(1);
            }

            Matcher bundledMatcher = DUCKDB_VERSION.matcher(bundledVersion);
            Matcher runtimeMatcher = DUCKDB_VERSION.matcher(runtimeVersion);
            assertThat(bundledMatcher.find(), is(true));
            assertThat(runtimeMatcher.find(), is(true));
            assertThat(bundledMatcher.group(1), is(runtimeMatcher.group(1)));
        }
    }

    @Test
    void bundledIonLoadsWithoutCommunityFallback() throws Exception {
        Assumptions.assumeTrue(bundledLinuxPlatform() != null, "Bundled ion extensions are only packaged for Linux workers");

        Queries task = Queries.builder()
            .id(IdUtils.create())
            .type(Queries.class.getName())
            .fetchType(Property.ofValue(FETCH_ONE))
            .sql(Property.ofExpression("""
                COPY (SELECT 1 AS a, 'x' AS b) TO '{{workingDir}}/out.ion' (FORMAT ION);
                SELECT a, b
                FROM read_ion('out.ion', columns := {a: 'BIGINT', b: 'VARCHAR'});
                """))
            .build();

        Queries.Output runOutput = task.run(runContextFactory.of(Map.of()));

        assertThat(runOutput.getOutputs().getFirst().getRow().get("a"), is(1L));
        assertThat(runOutput.getOutputs().getFirst().getRow().get("b"), is("x"));
        assertThat(
            logs.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("Failed to load bundled DuckDB extension 'ion'")),
            is(false)
        );
    }

    private static String bundledLinuxPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("linux")) {
            return null;
        }

        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (osArch) {
            case "amd64", "x86_64" -> "linux_amd64";
            case "aarch64", "arm64" -> "linux_arm64";
            default -> null;
        };
    }
}
