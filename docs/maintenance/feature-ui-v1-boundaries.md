# 機能GUIの公開契約と残る分離範囲

基点: `06be3c9dd4137efbffa5d4f91800881dbc07a5ff`。担当E、2026-10-27。

## 今回の境界

`FeatureUiV1` は機能GUI用の公開・版付き契約である。core側の
`FeatureUiBridgeV1` だけが既存のpackage-privateな `TableTool` と
`InternalFXMLLoader.showWindow` を呼ぶ。契約はウインドウの公開基底、開閉、
表の表示・幅・順序・選択・ショートカット、列設定画面、分割位置、画像拡大を持つ。
既存 `TableTool`、`InternalFXMLLoader`、`WindowController` 自体のAPIは変更しない。

各コントローラーの完全修飾名を維持するため、ウインドウ位置や表の幅などの
既存設定キーは変わらない。新たな整数セルは機能固有の `UseitemItem` を
ホストAPIのシグネチャに使わず、型引数で任意の行モデルを受け付ける。

| 配布JAR | 画面・専用部品の所有 |
|---|---|
| calculators | 経験値・海域経験値編集・遠征確認・確率・ラスダンの各コントローラー/FXML、確率計算、`ShortageShipItem` |
| charts | 資材・経験値チャートのコントローラー/FXML、`ResourceTable` と差分表示内部クラス |
| status | 入渠候補・基地航空隊・アイテム一覧のコントローラー/FXML、`RequireNdock`、`UseitemItem` |

基地航空隊とアイテム一覧は、既存メニューの同じFXML参照から機能JAR内の
実コントローラーを読む。登録済みFeature SPIは従来の7件を維持し、
この2件を重複してメニューへ追加しない。計10画面（7機能＋経験値編集＋追加2画面）が
対象となる。メニュー側の2件もSPIへ統一する変更は統合担当向けの後続差分とする。

## 独立更新の制約

公開GUI契約の版は1。破壊的変更ではV1のメソッドを差し替えず、新契約/API版を追加する。
各機能は既存の `logbook.bean` や `logbook.internal.kancolle` の公開型・計算・ログ読取も
使用するため、**API番号だけで任意の新旧core間の互換を保証しない**。
今回の単一機能更新は、検証済みの同じcoreハッシュを必要条件とする。
任意core版間の互換には、公開DTO・サービス契約への追加移行と別の互換試験が必要。

製品の管理ランチャーは全JARを同じクラスローダーへ載せる方式を維持する。
試験では別クラスローダーで実コントローラーを定義してJVMのpackage-private越境を検証するが、
製品のクラスローダー分離、ホットリロード、第三者プラグインの隔離は実装しない。

## P3で未分離の実装

| 領域 | coreに残る主なコード | 残る作業・競合境界 |
|---|---|---|
| 任務 | `QuestPane`、`QuestProgress`、`QuestConditionEditController`、`ArsenalQuestConditionEditController`、`QuestEditorDocument`、任務bean/判定/収集 | 現在の任務編集・到達観測・定義ルール担当と競合するため変更しない。収集/保存と画面契約を別途設計する。 |
| 艦娘 | `ShipController`、`ShipTablePane`、`ShipItem`、`FleetTabPane`/`FleetTabShipPane`/`FleetTabShipPopup`、`ParameterFilterPane` | 艦娘DTO、艦隊更新、装備参照、選択・フィルタ・設定保存の契約が必要。経験値画面専用 `ShortageShipItem` だけは今回移す。 |
| 装備 | `ItemController`、`ItemItemController`、`ItemAirBaseController`、`Item`、`ItemFilter`、`AirBaseItem`、`DetailItem` | 基地画面本体の分離と、装備一覧内の基地タブの分離を混同しない。後者は所有装備モデルと共通なので残る。 |
| 基地 | `AirBases`、`Mapinfo`/`SlotItem` 系bean/API更新、`DeckBuilder` の基地出力 | `AirBaseController` の実画面はstatusへ移す。共有制空計算・通信状態・デッキ出力の提供元はcoreに残し、公開サービス契約化が必要。 |
| 記録 | `BattleLogController`/`BattleLogCollect`/`BattleLogDetail`、`MissionLogController`/`MissionLogCollect`/`MissionLogDetail`、`CreateItemController`、戦闘詳細と各phase | ログ形式、既存保存先、並行更新、画面モデルを含む切り出しが必要。到達記録の復旧作業とは別。 |
| 補助 | `CaptureController`/`CaptureSaveController`/`ScreenCapture`、`Deck`/`DeckFleetPane`/`DeckShipPane`、`SeaAreaEditController`、`StatisticsPane`、`AkashiTimerPane`、`NdockPane`、`MissionPane`、`TextEditorPane` | アイテム一覧・入渠候補の専用行モデルは今回移す。キャプチャ/編成/統計/タイマー等は今回未分離。 |
| ホスト | `MainController`、`MainMenuController`、`ConfigController`、`VersionController`、`Tools`、`WindowController`、ロガー・プロキシ・更新機構 | ホストの責務として残す。共通API変更、既存CI、pom、verify_r3.shは統合担当が順次扱う。 |

## 検証の根拠

`FeatureUiBoundaryTest` は実classfileのホスト内部呼出しを検査する。
修正前は経験値コントローラーからの `InternalFXMLLoader` 参照で失敗した。
`FeatureUiBoundaryGuiTest` は異なるクラスローダーで入渠候補・基地・アイテム画面をロードし、
公開ダイアログ契約の適用コールバックと非表示時処理も実行する。

`SupportFeatureProbe` / `test_feature_support_integration.py` は元fatJAR/target/classesを
実行classpathへ含めず、分離JARだけで基地・アイテム画面を開く。
基地の制空値42・行動半径5・しきい値126、アイテム数12・未所持表示切替、
選択、表設定の保存と別Configでの再読込、合成履歴の保持を検査する。
読取・保存は新規一時領域の合成データだけ。途中の試験ログも別名で保持する。

以上はMint/本人データの動作確認、任務・艦娘装備・記録を含む全P3分離、
任意公開版の互換、本番適用、Release完了を意味しない。
