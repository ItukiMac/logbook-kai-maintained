package logbook.core;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import logbook.bean.MissionCondition;
import logbook.internal.json.JsonMappers;
import tools.jackson.databind.JsonNode;

/** Real opt-in public downloader into a NEW synthetic data store; restores retained baseline. */
public final class RoutinePublishedDataProbe {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static List<MissionCondition> nodes(MissionCondition value){var out=new ArrayList<MissionCondition>();out.add(value);if(value.getConditions()!=null)for(var c:value.getConditions())out.addAll(nodes(c));return out;}
    public static void main(String[] args)throws Exception {
        if(args.length!=5)throw new IllegalArgumentException("receipt publication baseline NEW-store verification-jar");
        var json=JsonMappers.builder().build();var receipt=json.readTree(Path.of(args[0]).toFile());var publication=json.readTree(Path.of(args[1]).toFile());
        Path baseline=Path.of(args[2]),store=Path.of(args[3]),jar=Path.of(args[4]);require(!Files.exists(store),"New store required");
        String expected=receipt.path("pack_sha256").asText(),baselineSha=receipt.path("baseline").path("sha256").asText(),revision=receipt.path("candidate_revision").asText();
        require(expected.matches("[0-9a-f]{64}")&&baselineSha.equals(hash(baseline)),"Baseline/expected identity");
        require(publication.path("source_commit").asText().equals(revision)&&publication.path("pack_sha256").asText().equals(expected),"Publication identity");
        String jarSha=hash(jar);require(jarSha.equals(receipt.path("verification_jar_sha256").asText()),"Actual verification JAR identity");
        String old=DefinitionPackInstaller.install(store,baseline,baselineSha);
        String previousAuto=System.getProperty(DataPackReleaseClient.AUTO_UPDATE_PROPERTY),previousRoot=System.getProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
        try {
            System.setProperty(DataPackReleaseClient.AUTO_UPDATE_PROPERTY,"true");System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY,store.toString());
            boolean installed=false;Exception last=null;
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
            for(int attempt=0;attempt<12&&System.nanoTime()<deadline;attempt++){
                try {
                    DataPackReleaseClient.refreshConfiguredData();
                    String active=DefinitionPackInstaller.activeDefinitionsRoot(store).getParent().getFileName().toString();
                    if(active.equals(expected)){installed=true;break;}
                    require(active.equals(old),"Unexpected concurrent data publication");
                } catch(java.io.IOException failure){last=failure;}
                Thread.sleep(8000);
            }
            if(!installed)throw new AssertionError("Published generation was not received through the actual production client",last);
            Path active=DefinitionPackInstaller.activeDefinitionsRoot(store);
            var manifest=json.readTree(active.getParent().resolve("manifest.json").toFile());require(manifest.path("source_revision").asText().equals(revision),"Committed source generation");
            for(var entry:receipt.path("observed_values").properties()){
                String relative=entry.getKey().substring("src/main/resources/logbook/".length());byte[] raw=Files.readAllBytes(active.resolve(relative));
                var typed=PreparedGameDefinitions.JSON.readValue(raw,MissionCondition.class);var clauses=nodes(typed);
                for(var field:entry.getValue().properties()){
                    int number=field.getValue().asInt();
                    boolean found=field.getKey().equals("flagship_min_level")
                        ? clauses.stream().anyMatch(c->Objects.equals(c.getOrder(),1)&&Objects.equals(c.getLevel(),number))
                        : clauses.stream().anyMatch(c->field.getKey().substring("fleet:".length()).equals(c.getCountType())&&Objects.equals(c.getValue(),number));
                    require(found,"Typed consumer lost numeric field "+entry.getKey()+" "+field.getKey());
                }
            }
            require(!DataPackReleaseClient.refreshConfiguredData(),"Same generation must be idempotent");
            require(DefinitionPackInstaller.restoreGeneration(store,old).equals(old),"Restore retained generation");
            Path restored=DefinitionPackInstaller.activeDefinitionsRoot(store);
            try(var archive=new ZipFile(baseline.toFile())){
                var entries=archive.entries();while(entries.hasMoreElements()){
                    var entry=entries.nextElement();if(!entry.getName().startsWith("definitions/"))continue;
                    require(Arrays.equals(archive.getInputStream(entry).readAllBytes(),Files.readAllBytes(restored.resolve(entry.getName().substring("definitions/".length())))),"Restored definition differs");
                }
            }
            require(Files.isDirectory(store.resolve("generations").resolve(expected)),"New generation retained");
            require(jarSha.equals(hash(jar))&&baselineSha.equals(hash(baseline)),"Original artifacts unchanged");
            require(!Files.exists(store.resolve("logbook-kai.jar"))&&!Files.exists(store.resolve("plugins")),"Data-only fixture");
            System.out.println("ROUTINE_PUBLIC_DATA_CYCLE_OK downloaded=true typed=true restored=true jar_unchanged=true production_runtime=false source="+revision+" generation="+expected);
        } finally {
            if(previousAuto==null)System.clearProperty(DataPackReleaseClient.AUTO_UPDATE_PROPERTY);else System.setProperty(DataPackReleaseClient.AUTO_UPDATE_PROPERTY,previousAuto);
            if(previousRoot==null)System.clearProperty(ExternalQuestDefinitions.ROOT_PROPERTY);else System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY,previousRoot);
        }
    }
}
