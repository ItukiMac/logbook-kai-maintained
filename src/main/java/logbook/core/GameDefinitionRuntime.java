package logbook.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import logbook.internal.logger.LoggerHolder;
import logbook.plugin.api.*;

/** Host definition boundary: bytes and validated caches publish together; no file writes or downloads. */
public final class GameDefinitionRuntime {
    private static volatile GameDefinitionService service;
    private static final ThreadLocal<Lease> PIN = new ThreadLocal<>();
    private static final GameDefinitionSnapshot EMPTY = new GameDefinitionSnapshot("runtime-not-started", Map.of());
    private GameDefinitionRuntime() {}
    public static synchronized void initialize() throws IOException {
        if(service != null) return;
        GameDefinitionService candidate = new GameDefinitionService(
            error -> LoggerHolder.get().warn("定義変更通知で例外", error), PreparedGameDefinitions::prepare);
        String configured = System.getProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
        if(configured != null && !configured.isBlank() && Files.exists(Path.of(configured).resolve("active.json"),LinkOption.NOFOLLOW_LINKS)) {
            candidate.reload(Path.of(configured));
        } else candidate.publish(bundled(configured));
        service=candidate; // An invalid initial generation never exposes a partially initialized service.
    }
    static GameDefinitionSnapshot bundled(String overlay) throws IOException {
        ClassLoader loader=LogBookCoreServices.getClassLoader();
        Set<String> names=new TreeSet<>();
        try(var stream=loader.getResourceAsStream("logbook/managed-definition-index.txt")) {
            if(stream==null) throw new IOException("Missing managed definition index");
            new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).lines().filter(s->!s.isBlank()).forEach(names::add);
        }
        // Include definition extensions in already selected plugins; use the original parent-first lookup order.
        try {for(var plugin:LogBookCoreServices.getPlugins()) {
            try(var jar=new JarFile(Path.of(plugin.getURL().toURI()).toFile())) {
                jar.stream().filter(e->!e.isDirectory()).map(e->e.getName()).filter(GameDefinitionRuntime::managed).forEach(names::add);
            } catch(java.net.URISyntaxException e){throw new IOException(e);}
        }} catch(IllegalStateException e) { /* No plugins in an isolated non-started container. */ }
        Path root=overlay==null || overlay.isBlank()?null:Path.of(overlay).toAbsolutePath().normalize();
        if(root!=null && Files.isDirectory(root)) {
            if(!root.toRealPath().equals(root)) throw new IOException("Symlinked overlay root");
            for(String category:List.of("quest","eventquest","mission","map","kousyou","supplemental")) {
                Path directory=root.resolve(category);
                if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) try(var paths=Files.walk(directory)) {
                    for(Path file:paths.toList()) {
                        if(Files.isSymbolicLink(file)) throw new IOException("Symlinked overlay definition");
                        if(Files.isRegularFile(file) && file.toString().endsWith(".json")) names.add("logbook/"+root.relativize(file).toString().replace(File.separatorChar,'/'));
                    }
                }
            }
        }
        Map<String,byte[]> data=new LinkedHashMap<>();long total=0;
        if(names.size()>GameDefinitionSnapshot.MAX_FILES) throw new IOException("Too many bundled definitions");
        for(String name:names) {
            GameDefinitionSnapshot.validateName(name);
            Path external=root==null?null:root.resolve(name.substring("logbook/".length()));
            InputStream stream;
            if(external!=null && Files.exists(external,LinkOption.NOFOLLOW_LINKS)) {
                if(!Files.isRegularFile(external,LinkOption.NOFOLLOW_LINKS) || !external.toRealPath().equals(external)) throw new IOException("Unsafe overlay path");
                stream=Files.newInputStream(external);
            } else stream=loader.getResourceAsStream(name);
            if(stream==null) throw new IOException("Indexed definition missing: "+name);
            try(stream) {
                byte[] bytes=stream.readNBytes((int)(GameDefinitionSnapshot.MAX_BYTES-total)+1);
                total+=bytes.length;
                if(total>GameDefinitionSnapshot.MAX_BYTES) throw new IOException("Definition size limit");
                data.put(name,bytes);
            }
        }
        var provisional=new GameDefinitionSnapshot("bundled",data);
        return new GameDefinitionSnapshot("bundled-"+provisional.fingerprint(),data);
    }
    public static boolean active(){return service!=null;}
    public static GameDataView view(){var value=service;if(value==null) throw new IllegalStateException("Definition runtime not initialized");return value;}
    public static GameDefinitionSnapshot reloadConfigured() throws IOException {
        var value=service;if(value==null)throw new IllegalStateException("Definition runtime not initialized");
        String root=System.getProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
        if(root!=null && !root.isBlank() && Files.exists(Path.of(root).resolve("active.json"),LinkOption.NOFOLLOW_LINKS)) return value.reload(Path.of(root));
        return value.publish(bundled(root));
    }
    public static GameDefinitionSnapshot reload(Path root) throws IOException {
        var value=service;if(value==null) throw new IllegalStateException("Definition runtime not initialized");
        return value.reload(root); // Explicit in-memory reload, not installer/active-pointer mutation.
    }
    public static GameDefinitionScope pin(){
        Lease previous=PIN.get();
        var value=service;
        return new Lease(previous, previous!=null?previous.publication:value==null?null:value.publication());
    }
    public static GameDefinitionScope pin(GameDefinitionSnapshot snapshot){
        Objects.requireNonNull(snapshot);
        Lease previous=PIN.get();
        if(previous!=null && previous.publication!=null && previous.publication.snapshot()==snapshot) return new Lease(previous,previous.publication);
        var value=service;var published=value==null?null:value.publication();
        var selected=published!=null && published.snapshot()==snapshot?published:
            new GameDefinitionService.Publication(snapshot,PreparedGameDefinitions.prepare(snapshot));
        return new Lease(PIN.get(),selected);
    }
    private static GameDefinitionService.Publication publication(){
        Lease lease=PIN.get();if(lease!=null) return lease.publication;
        var value=service;return value==null?null:value.publication();
    }
    public static GameDefinitionSnapshot snapshot(){var value=publication();return value==null?EMPTY:value.snapshot();}
    public static boolean managed(String name){try{GameDefinitionSnapshot.validateName(name);return true;}catch(IllegalArgumentException e){return false;}}
    static InputStream open(String name){return snapshot().openResource(name).orElse(null);}
    static URL url(String name){
        var held=snapshot();if(!held.resourceNames().contains(name)) return null;
        try {return new URL(null,"logbook-definition:/"+held.generation()+"/"+name,new URLStreamHandler(){
            protected URLConnection openConnection(URL url){return new URLConnection(url){
                public void connect(){connected=true;}
                public InputStream getInputStream() throws IOException {return held.openResource(name).orElseThrow(()->new FileNotFoundException(name));}
            };}
        });}catch(MalformedURLException e){throw new IllegalArgumentException(e);}
    }
    private static PreparedGameDefinitions prepared(){var value=publication();return value==null?null:(PreparedGameDefinitions)value.prepared();}
    public static Map<String,String> mapping(){var value=prepared();return value==null?null:value.mapping;}
    /** Null means absent in an active generation, not permission to fall back to legacy data. */
    public static List<Integer> shipItems(int id){var value=prepared();var ship=value==null?null:value.ships.get(id);return ship==null?null:ship.items();}
    public static int[] shipMinimums(int id){var value=prepared();var ship=value==null?null:value.ships.get(id);return ship==null?null:new int[]{ship.minTaisen(),ship.minSakuteki(),ship.minKaihi()};}
    public static Map<String,List<String>> arsenalDays(){var value=prepared();return value==null?null:value.arsenalDays;}
    static synchronized void stopForTest(){if(PIN.get()!=null)throw new IllegalStateException("Open lease");service=null;}
    private static final class Lease implements GameDefinitionScope {
        final Lease previous;final GameDefinitionService.Publication publication;final Thread owner=Thread.currentThread();boolean closed;
        Lease(Lease previous,GameDefinitionService.Publication publication){this.previous=previous;this.publication=publication;PIN.set(this);}
        public GameDefinitionSnapshot snapshot(){if(closed)throw new IllegalStateException("Closed definition lease");return publication==null?EMPTY:publication.snapshot();}
        public void close(){
            if(Thread.currentThread()!=owner)throw new IllegalStateException("Definition lease belongs to another thread");
            if(closed)return;
            if(PIN.get()!=this)throw new IllegalStateException("Close nested definition leases in reverse order");
            if(previous==null)PIN.remove();else PIN.set(previous);closed=true;
        }
    }
}
