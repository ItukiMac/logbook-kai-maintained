# 命名と境界

製品名は `logbook-kai2`。core / ships / equipment / quests / records / charts / calculators / bridge / sharing を正式なモジュールIDとします。

配布JARは `logbook-kai2-core.jar` と `logbook-kai2-feature-<id>.jar`。内部のブラウザ接続と共有のパッケージは `logbook.features.bridge` / `logbook.features.sharing`。

旧ID klb/kqsの互換別名は追加しません。既存のブラウザ通信・利用中の外部出力形式の識別子は、連携確認なしに変更しません。

| 概念 | 内部用語 |
| --- | --- |
| 艦娘 | ship |
| 艦隊 | fleet |
| 装備 | equipment |
| 任務 | quest |
| 報酬受領済み | claimed |
| 履歴・記録 | history / record（用途を明記） |

sharingは公開された情報を出力し、任務状態や記録の正本を所有しません。questsは任務管理、recordsは記録の閲覧を担当します。

変数名変更は意味を読んで行います。ゲームAPIのdeck/item、保存キー、FXML参照、反射利用、クラス名由来の設定ファイルは一括置換しません。再取得可能な旧設定の互換対応は不要です。履歴・手動情報への参照は維持または明示的に取り込みます。
