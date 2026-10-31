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

## 整理結果と境界上の名称

内部製品名、モジュールID、パッケージ・サービス登録、配布JAR、起動/更新ファイル、主要な任務管理入力変数を統一しました。主な入力名はquestIdInput / editionInput / periodInput / reasonInput / recordedAtInputです。

以下は意味を変える改名をしない境界です。
- ゲームAPI由来のbeanプロパティ（deck、slotitem、mst等）: 外部フィールドとの対応を保持。
- 既存CSV・JSON・任務台帳のキー: 再取得不能なデータの形式を保持。
- 公開V1 APIおよびFXMLコントローラー参照: JavaコンパイルとFXML起動試験で整合性を検査。
- ブラウザ通信のKCP1と任務JSONのkqs.quest-snapshot.v2: 利用中の連携形式として保持。画面・製品名として使用しない。

任意の一文字ループ変数や既存外部モデルまで機械的に置換する方針ではありません。新規内部型はUpperCamelCase、ローカル変数は役割が分かるlowerCamelCaseに揃えます。命名・配置の自動検査はscripts/check_product_conventions.py、参照と振る舞いはJava/Python/GUIの試験で検証します。
