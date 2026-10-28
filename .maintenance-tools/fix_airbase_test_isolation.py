from pathlib import Path

p = Path('src/test/java/logbook/core/AirBaseStatusContractTest.java')
s = p.read_text()
old = '    @org.junit.jupiter.api.BeforeAll static void initResourceReader() throws Exception { LogBookCoreServices.init(List.of()); }\n    @org.junit.jupiter.api.AfterAll static void closeResourceReader() throws Exception { LogBookCoreServices.closeContainer(); }'
assert s.count(old) == 1
replacement = '''    /** Load actual classpath resources without owning the suite's core container.
     * The short-lived static resource adapter is closed before tests run;
     * calculations, inputs and expected values are not mocked.
     */
    @org.junit.jupiter.api.BeforeAll static void loadCalculationResources() throws Exception {
        ClassLoader loader = AirBaseStatusContractTest.class.getClassLoader();
        try (var resources = org.mockito.Mockito.mockStatic(LogBookCoreServices.class)) {
            resources.when(LogBookCoreServices::getClassLoader).thenReturn(loader);
            resources.when(() -> LogBookCoreServices.getResourceAsStream(org.mockito.Mockito.anyString()))
                    .thenAnswer(call -> loader.getResourceAsStream(call.getArgument(0, String.class)));
            Class.forName("logbook.internal.kancolle.Ships", true, loader);
        }
    }'''
p.write_text(s.replace(old, replacement))
doc = Path('docs/maintenance/airbase-status-boundary-20261028.md')
doc.write_text(doc.read_text() + '''
## 初回全体CIの失敗と試験ライフサイクル修正

初回head 5e84aebe91b08c3b364fdf5d57949ad7b015bda3は、個別23件が成功しても全体CIで失敗した。
新試験が共用コアを初期化・終了するため、後続の設定パス/装備試験へ影響する構造があった。
修正は試験の資源読込に限定し、共用コアのinit/closeを行わない。
短いスコープ内で実クラスパス資源を提供し、既存Shipsの静的定数を読み込む。
各試験の実行前にその資源アダプターを閉じ、数式・入力値・期待値は変更しない。
関連試験を同じJVM・明示した順序でまとめて実行し、個別GUIも再確認する。
初回失敗と修正後の実結果は別々の証跡として保持する。全体の成功は最終headのCIで別途確認する。
''')
print('AIRBASE_TEST_RESOURCE_ISOLATION_APPLIED production_source_unchanged=true expectations_unchanged=true')
