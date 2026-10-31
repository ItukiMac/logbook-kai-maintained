package logbook.internal.logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.management.ManagementFactory;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;

class RuntimeWiringTest {
    static final class FinalFixture {
        int value() {
            return 7;
        }
    }

    @Test
    void mockitoIsAnExplicitStartupAgent() {
        assertTrue(ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch(arg -> arg.startsWith("-javaagent:") && arg.contains("mockito-core")));
        var mocked = mock(FinalFixture.class);
        when(mocked.value()).thenReturn(42);
        assertEquals(42, mocked.value());
    }

    @Test
    void slf4jUsesTheExistingLog4jBackend() {
        assertEquals("org.apache.logging.slf4j.Log4jLoggerFactory",
                LoggerFactory.getILoggerFactory().getClass().getName());
    }

    @Test
    void exactlyOneSlf4jProviderIsInstalled() {
        var providers = ServiceLoader.load(SLF4JServiceProvider.class).stream()
                .map(provider -> provider.type().getName()).toList();
        assertEquals(java.util.List.of("org.apache.logging.slf4j.SLF4JServiceProvider"), providers);
    }
}
