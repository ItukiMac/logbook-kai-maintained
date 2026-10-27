package logbook.internal.json;

import tools.jackson.databind.json.JsonMapper;

/** Host JSON construction boundary. Retains the existing persisted JSON defaults.
 * Jackson 2 remains a runtime-only compatibility dependency for existing plugins;
 * host code uses immutable Jackson 3 mappers. Protocol-specific strict options
 * are added by the caller and are never disabled by this factory.
 */
public final class JsonMappers {
    private JsonMappers() { }
    public static JsonMapper.Builder builder() {
        return JsonMapper.builderWithJackson2Defaults();
    }
}
