package logbook.internal.logger;

/** Run with ONLY the assembled application JAR and target/test-classes on the classpath. */
public final class PackagedLoggingProbe {
    public static void main(String[] args) {
        String actual = org.slf4j.LoggerFactory.getILoggerFactory().getClass().getName();
        if (!"org.apache.logging.slf4j.Log4jLoggerFactory".equals(actual)) {
            throw new IllegalStateException("Assembled application lost its SLF4J provider: " + actual);
        }
        long providers = java.util.ServiceLoader.load(org.slf4j.spi.SLF4JServiceProvider.class).stream().count();
        if (providers != 1) throw new IllegalStateException("Expected one assembled provider, got " + providers);
        System.out.println("PACKAGED_LOGGING_OK " + actual);
    }
}
