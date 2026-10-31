package logbook.internal.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import org.controlsfx.control.textfield.AutoCompletionBinding.ISuggestionRequest;
import javafx.scene.control.TextField;
import javafx.util.Callback;

/** Suggestions for a {@link TextField}; the legacy callback remains binary compatible. */
public class SuggestSupport implements Callback<ISuggestionRequest, Collection<String>> {
    private List<String> values;
    private BiPredicate<String, String> predicate;

    public SuggestSupport(String... values) {
        this(String::startsWith, values);
    }

    public SuggestSupport(BiPredicate<String, String> predicate, String... values) {
        this(predicate, Arrays.asList(values));
    }

    public SuggestSupport(List<String> values) {
        this(String::startsWith, new ArrayList<>(values));
    }

    public SuggestSupport(BiPredicate<String, String> predicate, List<String> values) {
        this.predicate = predicate;
        this.values = values;
    }

    @Override
    public Collection<String> call(ISuggestionRequest param) {
        return suggest(param.getUserText());
    }

    /** Does not construct or access a ControlsFX popup/skin. */
    public Collection<String> suggest(String text) {
        if (text == null || text.isEmpty())
            return new ArrayList<>(this.values);
        return this.values.stream().filter(e -> this.predicate.test(e, text))
                .collect(Collectors.toList());
    }
}
