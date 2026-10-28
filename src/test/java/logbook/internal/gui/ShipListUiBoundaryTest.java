package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Inspect nested cells/filters too; outer-controller checks miss runtime package access. */
class ShipListUiBoundaryTest {
    @Test void shipUiDoesNotLinkPrivateHostGuiHelpers() throws Exception {
        var families=List.of("ShipController","ShipTablePane","ParameterFilterPane");
        var denied=List.of("TableTool","InternalFXMLLoader","Tools","WindowController");
        Path classes=Path.of(ShipController.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("logbook/internal/gui");
        int count=0;
        try(var paths=Files.list(classes)) {
            for(Path file:paths.filter(p->p.toString().endsWith(".class")).toList()) {
                String name=file.getFileName().toString();
                if(families.stream().noneMatch(f->name.equals(f+".class")||name.startsWith(f+"$")))continue;
                count++;
                String constants=new String(Files.readAllBytes(file),StandardCharsets.ISO_8859_1);
                for(String type:denied)assertFalse(constants.contains("logbook/internal/gui/"+type),name+" links private host GUI "+type);
            }
        }
        assertTrue(count>=3);
    }
}
