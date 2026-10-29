from pathlib import Path
source=Path(__file__).with_name('quest_editor_read_tests.py').read_text()
a=source.index('    @Test void filterValuesAndInsertionOrderAreRetained()')
b=source.index('    @Test void nestedFleetConditions',a)
source=source[:a]+r'''    @Test void filterValuesAndLegacyIterationOrderAreRetained()throws Exception {
        String json="{\"filter\":{\"area\":[\"7-5\",\"1-6\"],\"deckIds\":[3,1],\"noRetreat\":true}}";
        var f=parse(json).filter();
        var legacy=QuestDocumentValidation.mapper().readValue(json,AppQuestCondition.class).getFilter();
        assertEquals(Set.of("7-5","1-6"),f.area());assertEquals(Set.of(3,1),f.deckIds());assertTrue(f.noRetreat());
        assertEquals(new ArrayList<>(legacy.getArea()),new ArrayList<>(f.area()));
        assertEquals(new ArrayList<>(legacy.getDeckIds()),new ArrayList<>(f.deckIds()));
        assertThrows(UnsupportedOperationException.class,()->f.area().clear());
    }
'''+source[b:]
exec(compile(source,'quest_editor_read_legacy_order_oracle','exec'),globals())
