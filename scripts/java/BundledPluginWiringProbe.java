import java.util.ServiceLoader;
import logbook.plugin.lifecycle.StartUp;
import logbook.listener.ContentListenerSpi;

/** Validate packaged provider discovery without starting browser/server/GUI activity. */
public final class BundledPluginWiringProbe {
    private static <T> void check(Class<T> service) {
        var providers = ServiceLoader.load(service).stream().map(ServiceLoader.Provider::type).toList();
        for (var item : java.util.Map.of(
                "local.kancolle.bridge.KancolleBridgeStartUp", "klb",
                "local.kancolle.kqs.KqsLogbookPlugin", "kqs").entrySet()) {
            if (service == ContentListenerSpi.class && item.getValue().equals("klb")) continue;
            var found = providers.stream().filter(type -> type.getName().equals(item.getKey())).toList();
            if (found.size() != 1) throw new AssertionError("Provider count: " + item.getKey());
            var source = found.get(0).getProtectionDomain().getCodeSource().getLocation().getPath();
            if (!source.endsWith("/logbook-feature-" + item.getValue() + ".jar"))
                throw new AssertionError("Wrong provider owner: " + source);
        }
    }
    public static void main(String[] args) throws Exception {
        check(StartUp.class); check(ContentListenerSpi.class);
        Class.forName("tools.jackson.databind.ObjectMapper");
        Class.forName("com.fasterxml.jackson.annotation.JsonIgnore");
        for (String old : java.util.List.of("com.fasterxml.jackson.databind.ObjectMapper", "com.fasterxml.jackson.core.JsonFactory")) {
            try { Class.forName(old); throw new AssertionError("Jackson2 shipped: " + old); }
            catch (ClassNotFoundException expected) { }
        }
        System.out.println("BUNDLED_PLUGIN_WIRING_OK klb=1 kqs=1 jackson2_runtime=false");
    }
}
