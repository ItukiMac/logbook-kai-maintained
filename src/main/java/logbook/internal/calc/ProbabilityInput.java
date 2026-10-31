package logbook.internal.calc;

import java.util.List;

/** Text-input boundary shared by the existing UI and headless consumers. */
public final class ProbabilityInput {
    private ProbabilityInput() {
    }

    public record Result(List<ProbabilityCalculator.Point> points, String message) {
        public Result {
            points = List.copyOf(points);
        }

        public boolean valid() {
            return message.isEmpty();
        }
    }

    public static Result evaluate(String percent, String limit, String step) {
        if (blank(percent) || blank(limit) || blank(step)) {
            return error("確率・上限値・増分をすべて入力してください。");
        }
        final double p;
        final int l, s;
        try {
            p = Double.parseDouble(percent.strip());
        } catch (NumberFormatException ex) {
            return error("ドロップ確率は数値で入力してください。");
        }
        try {
            l = Integer.parseInt(limit.strip());
            s = Integer.parseInt(step.strip());
        } catch (NumberFormatException ex) {
            return error("上限値と増分は1〜2147483647の整数で入力してください。");
        }
        String message = switch (ProbabilityCalculator.validate(p, l, s)) {
        case NONE -> "";
        case PERCENT_RANGE -> "確率は0より大きく100より小さい数値で入力してください。";
        case POSITIVE_COUNTS -> "上限値と増分は1以上で入力してください。";
        case ROW_BUDGET -> "計算行数の上限（10000行）を超えます。上限値を下げるか、増分を大きくしてください。";
        case UNDERFLOW -> "確率が小さすぎて計算できません。より大きな値を入力してください。";
        };
        return message.isEmpty() ? new Result(ProbabilityCalculator.calculate(p, l, s), "") : error(message);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static Result error(String message) {
        return new Result(List.of(), message);
    }
}
