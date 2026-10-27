from pathlib import Path
import sys,re,json

BASE='7004a3e5937b47328f5c6bfc80b1f663a6a5c1f5'

def new(path,text):
    p=Path(path)
    assert not p.exists(),path
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(text,encoding='utf8')

def edit(path,changes):
    p=Path(path);raw=p.read_bytes();ending='\r\n' if b'\r\n' in raw else '\n'
    text=raw.decode().replace('\r\n','\n')
    for old,value,count in changes:
        assert text.count(old)==count,(path,old,text.count(old),count)
        text=text.replace(old,value)
    p.write_bytes(text.replace('\n',ending).encode())

new('src/test/java/logbook/internal/gui/RecordUiBoundaryTest.java',r'''package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Includes nested dialogs/cells; checking only outer controllers misses JVM access failures. */
class RecordUiBoundaryTest {
    @Test void recordBytecodeDoesNotDependOnHostGuiImplementation() throws Exception {
        List<String> families = List.of("BattleLogController", "MissionLogController", "CreateItemController");
        List<String> prohibited = List.of("TableTool", "TreeTableTool", "InternalFXMLLoader",
                "Tools", "WindowController", "BattleDetail");
        Path classes = Path.of(BattleLogController.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("logbook/internal/gui");
        int checked = 0;
        try (var paths = Files.list(classes)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = file.getFileName().toString();
                if (families.stream().noneMatch(f -> name.equals(f + ".class") || name.startsWith(f + "$"))) continue;
                checked++;
                String symbols = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                for (String internal : prohibited)
                    assertFalse(symbols.contains("logbook/internal/gui/" + internal), name + " links host GUI " + internal);
            }
        }
        assertTrue(checked >= families.size(), "No record classfiles inspected");
    }
}
''')
if sys.argv[1]=='baseline':sys.exit(0)
raise SystemExit('Use baseline first, then execute the separate implementation script')
