package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SuggestSupportTest {
    @Test void defaultsToPrefix() {
        assertEquals(List.of("alpha"), new ArrayList<>(new SuggestSupport("alpha", "beta").suggest("al")));
    }
    @Test void preservesContainsPredicate() {
        assertEquals(List.of("beta"), new ArrayList<>(new SuggestSupport(String::contains, "alpha", "beta").suggest("et")));
    }
    @Test void emptyQueryDoesNotExposeProviderList() {
        var provider = new SuggestSupport("a", "b");
        provider.suggest("").clear();
        assertEquals(2, provider.suggest("").size());
    }
    @Test void listConstructorRetainsItsSnapshot() {
        var input = new ArrayList<>(List.of("a"));
        var provider = new SuggestSupport(input);
        input.add("b");
        assertEquals(List.of("a"), new ArrayList<>(provider.suggest("")));
    }
}
