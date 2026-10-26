package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import javafx.scene.control.CheckBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="logbook.gui.tests", matches="true")
@Timeout(20)
class RestoredCheckBoxGroupGuiTest {
    @BeforeAll static void start() throws Exception { FxTestSupport.start(); }
    @Test void restoredNonePartialAndAllAgreeWithFiltersAndRemainBound() throws Exception {
        FxTestSupport.fx(() -> {
            for (int mask=0;mask<4;mask++) {
                CheckBox parent=new CheckBox();parent.setAllowIndeterminate(true);
                CheckBox a=new CheckBox(), b=new CheckBox();
                a.setSelected((mask&1)!=0);b.setSelected((mask&2)!=0);
                RestoredCheckBoxGroup.bind(parent,List.of(a,b));
                assertEquals(mask==3,parent.isSelected());
                assertEquals(mask==1||mask==2,parent.isIndeterminate());
                assertEquals((mask&1)!=0,a.isSelected());assertEquals((mask&2)!=0,b.isSelected());
                a.setSelected(true);b.setSelected(true);
                assertTrue(parent.isSelected());assertFalse(parent.isIndeterminate());
                parent.setSelected(false);
                assertFalse(a.isSelected());assertFalse(b.isSelected());
            }
            return null;
        });
    }
}
