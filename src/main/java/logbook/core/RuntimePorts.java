package logbook.core;

/** Validated local ports. Explicit properties override fresh kai2 defaults. */
public final class RuntimePorts {
    public static final int DEFAULT_BRIDGE = 8892;
    public static final int DEFAULT_PROXY = 8889;

    private RuntimePorts() {
    }

    public static int bridge() {
        return parse(System.getProperty("logbook.bridge.port", "8892"));
    }

    public static int proxy() {
        return parse(System.getProperty("logbook.proxy.port", "8889"));
    }

    public static int parse(String value) {
        if (value == null || !value.matches("[0-9]{1,5}")) {
            throw new IllegalArgumentException("Port must be a decimal number from 1024 to 65535");
        }
        int port = Integer.parseInt(value);
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Port must be from 1024 to 65535");
        }
        return port;
    }

    public static void validatePair(int bridge, int proxy) {
        parse(Integer.toString(bridge));
        parse(Integer.toString(proxy));
        if (bridge == proxy) {
            throw new IllegalArgumentException("Bridge and proxy ports must be different");
        }
    }
}
