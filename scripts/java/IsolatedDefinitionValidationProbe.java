package logbook.core;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** The only active.json written is inside a required NEW disposable validation directory. */
public final class IsolatedDefinitionValidationProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("stage directory and NEW validation directory required");
        Path stage = Path.of(args[0]).toAbsolutePath().normalize();
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        for (Path p : List.of(stage, root)) {
            for (Path q = p; q != null; q = q.getParent())
                if (Files.isSymbolicLink(q))
                    throw new IllegalArgumentException("Symlinked path");
        }
        var manifest = PreparedGameDefinitions.JSON.readTree(Files.readString(stage.resolve("stage.json")));
        if (!"logbook-isolated-candidate-v1".equals(manifest.path("format").asText()))
            throw new IllegalArgumentException("Unknown stage");
        Path pack = stage.resolve("candidate-data-pack.zip");
        if (Files.isSymbolicLink(pack))
            throw new IllegalArgumentException("Symlinked pack");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pack)));
        if (!hash.equals(manifest.path("candidate_pack_sha256").asText()))
            throw new IllegalArgumentException("Stage pack changed");
        Files.createDirectory(root); // Refuses every existing directory, including previous validations.
        DefinitionPackInstaller.install(root, pack, hash);
        var snapshot = GameDefinitionLoader.load(root);
        PreparedGameDefinitions.prepare(snapshot); // All six actual typed consumers, not syntax alone.
        if (snapshot.resourceNames().size() != manifest.path("definition_count").asInt())
            throw new IllegalArgumentException("Inventory mismatch");
        System.out.println("ISOLATED_TYPED_VALIDATION_OK files=" + snapshot.resourceNames().size() + " sha256=" + hash
                + " production=false");
    }
}
