package logbook.core;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;

/** Versioned, fail-closed format gate. Old hosts reject V2 at their existing format check. */
final class DefinitionPackCompatibility {
    static final String LEGACY_FORMAT = "logbook-data-pack-v1";
    static final String CAPABILITY_FORMAT = "logbook-data-pack-v2";
    static final String DECK_IDS = "quest-filter-deck-ids-v1";
    static final String NO_RETREAT = "quest-filter-no-retreat-v1";
    private static final Set<String> SUPPORTED = Set.of(DECK_IDS, NO_RETREAT);
    private DefinitionPackCompatibility() { }

    static Set<String> requireManifest(JsonNode manifest) throws IOException {
        if (manifest == null || !manifest.isObject()) throw new IOException("Invalid data manifest");
        String format = manifest.path("format").asText();
        JsonNode required = manifest.path("required_capabilities");
        if (LEGACY_FORMAT.equals(format)) {
            if (!required.isMissingNode()) throw new IOException("V1 must not declare capability requirements");
            return Set.of();
        }
        if (!CAPABILITY_FORMAT.equals(format) || !required.isArray() || required.isEmpty())
            throw new IOException("Unsupported or incomplete capability data manifest");
        Set<String> result = new HashSet<>();
        for (JsonNode item : required) {
            if (!item.isTextual() || !SUPPORTED.contains(item.textValue()) || !result.add(item.textValue()))
                throw new IOException("Unknown or duplicate required data capability");
        }
        return Set.copyOf(result);
    }

    static void requireDefinition(String path, JsonNode definition, Set<String> declared) throws IOException {
        if (definition == null) throw new IOException("Empty definition JSON: " + path);
        if (!(path.startsWith("definitions/quest/") || path.startsWith("definitions/eventquest/")
                || path.startsWith("logbook/quest/") || path.startsWith("logbook/eventquest/"))) return;
        JsonNode filter = definition.path("filter");
        if (filter.has("deckIds") && !declared.contains(DECK_IDS))
            throw new IOException("Missing deck-ID capability declaration: " + path);
        if (filter.has("noRetreat") && !declared.contains(NO_RETREAT))
            throw new IOException("Missing retreat capability declaration: " + path);
    }
}
