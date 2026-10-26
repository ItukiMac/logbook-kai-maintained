package logbook.features;
import java.util.List;
import logbook.plugin.api.FeatureModule;
public final class CalculatorFeatures implements FeatureModule {
    @Override public List<Feature> features() {return List.of(
            new Feature("experience", "経験値計算機", "logbook/gui/calc_exp.fxml", Group.CALCULATOR, 10, "Ctrl+C"),
            new Feature("mission-check", "遠征条件確認", "logbook/gui/missioncheck.fxml", Group.CALCULATOR, 20, ""),
            new Feature("probability", "確率計算機", "logbook/gui/prob_calc.fxml", Group.CALCULATOR, 30, ""),
            new Feature("last-dance", "ラスダン計算機", "logbook/gui/last_dance_calc.fxml", Group.CALCULATOR, 40, ""));}
}
