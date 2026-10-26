package logbook.internal.gui;

import java.util.List;
import javafx.scene.control.CheckBox;

/** Restore the aggregate before installing bidirectional listeners; never change saved child choices. */
final class RestoredCheckBoxGroup {
    private RestoredCheckBoxGroup() { }
    static void bind(CheckBox parent, List<CheckBox> children) {
        boolean all = !children.isEmpty() && children.stream().allMatch(CheckBox::isSelected);
        boolean any = children.stream().anyMatch(CheckBox::isSelected);
        parent.setSelected(all);
        parent.setIndeterminate(any && !all);
        Tools.Controls.bindChildCheckBoxes(parent, children);
    }
}
