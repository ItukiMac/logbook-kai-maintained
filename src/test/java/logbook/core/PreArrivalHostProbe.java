package logbook.core;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Probe a real prior V2-capable host; no current-host classes leak into its loader. */
public final class PreArrivalHostProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("prior JAR and NEW fixture directory required");
        Path jar=Path.of(args[0]).toAbsolutePath(), root=Path.of(args[1]).toAbsolutePath();
        if (!Files.isRegularFile(jar) || Files.isSymbolicLink(jar)) throw new IOException("Invalid prior JAR");
        Files.createDirectory(root);
        var known=DefinitionPackCompatibilityTest.pack(root,DefinitionPackCompatibility.CAPABILITY_FORMAT,
                List.of(DefinitionPackCompatibility.DECK_IDS),"{\"filter\":{\"deckIds\":[1]}}");
        var arrival=DefinitionPackCompatibilityTest.pack(root,DefinitionPackCompatibility.CAPABILITY_FORMAT,
                List.of(DefinitionPackCompatibility.PORT_ARRIVAL),"{\"conditions\":[{\"area\":[\"1-6\"],\"arrival\":true,\"count\":1}]}");
        try(var loader=new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            var installer=Class.forName("logbook.core.DefinitionPackInstaller",true,loader);
            if (installer.getClassLoader()!=loader) throw new AssertionError("Prior host was not isolated");
            var install=installer.getDeclaredMethod("install",Path.class,Path.class,String.class);install.setAccessible(true);
            Path oldRoot=root.resolve("prior");install.invoke(null,oldRoot,known.path(),known.sha());
            byte[] before=Files.readAllBytes(oldRoot.resolve("active.json"));
            try {install.invoke(null,oldRoot,arrival.path(),arrival.sha());throw new AssertionError("Prior V2 host accepted unknown arrival semantics");}
            catch(InvocationTargetException expected){if(!(expected.getCause() instanceof IOException))throw expected;}
            if(!Arrays.equals(before,Files.readAllBytes(oldRoot.resolve("active.json"))))throw new AssertionError("Prior active pointer changed");
            if(!Files.exists(oldRoot.resolve("generations").resolve(known.sha())))throw new AssertionError("Retained generation lost");
        }
        Path current=root.resolve("current");DefinitionPackInstaller.install(current,arrival.path(),arrival.sha());
        PreparedGameDefinitions.prepare(GameDefinitionLoader.load(current));
        System.out.println("PRE_ARRIVAL_HOST_GATE_OK prior-v2=accepted arrival=refused active=preserved current-arrival=accepted");
    }
}
