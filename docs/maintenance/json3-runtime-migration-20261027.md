# Jackson 3本体移行とJavaFX 27の明示構成 — 2026-10-27

基点はPR #56統合済み `b2d224adff26cedf0f049be4b454140a9fc8d176`。
これはソースと隔離環境での更新。Mint、本人の設定/履歴/プラグイン、OS、auto.sh、既存バックアップには触れない。

## 実装対象

- 本体のJSON読込/保存/定義検査/到達記録/編集処理を `tools.jackson` 3.2.4へ移行する。
- `JsonMappers`を生成境界とし、可変ObjectMapper設定をimmutable builderへ変更する。
- `builderWithJackson2Defaults()`により既存保存形式の互換を優先する。個々の読込口の重複キー/後続文書/型/サイズ検査は維持する。
- 旧プラグインが直接参照する `com.fasterxml.jackson` 2.22.4はruntime専用の互換層として保持する。本体を2で動かしたまま3へ更新したと表現せず、逆に2を完全削除したとも表現しない。
- annotationsはJackson自身の設計どおり共通2系を使用する。
- 旧コンパイル時例外から新しい実行時例外への変更は、読込失敗・バックアップ復旧・不正データ拒否の経路を保つよう確認する。

## 独立した互換試験

新旧両エンジンを同一試験で実際に使う10試験を追加。合成TempDirだけを対象に、旧設定→新Config→旧エンジン、未知項目の読込、破損primaryから旧backupへのfallback、旧journalのSHA256検証、新追記の旧エンジン再計算、旧到達record、破損記録の保持を確認する。旧版データを書き直す一括変換は行わない。

既存の単独試験・全GUI・任務判定・KLB/KQS・分割機能の通常起動更新復元・B/C/D/E結合試験は引き続き必要。コンパイル成功だけで移行完了とはしない。最終実行head、試験件数、失敗/修正、レビュー/統合はPRの実結果へ記録する。

## 実行基線の区別

| 構成 | Java | JavaFX | 本体JSON |
| --- | --- | --- | --- |
| 既定互換ビルド | 21以上、classfile21 | 21.0.13 | Jackson3.2.4 |
| `-Pmodern-runtime`の明示ビルド | 25以上、classfile25 | 27.0.1 | Jackson3.2.4 |

JavaFX27はJDK25以上を要求する（https://openjfx.io/highlights/27/）。現在のJava21利用環境を黙って起動不能にしないよう、新基線は明示profileと専用CIで検証する。JDK25でビルドしただけで自動的にmodern profileを選ばない。ネイティブMintでのIME/実Chrome/ゲーム通信は別の未完了。

専用CIは実効POM、解決された7つのJavaFX部品、Launcherのclassfile69、全既存検証と追加10試験の非skipを要求する。公開/タグ作成はしない。単に宣言された版番号を見て検証済みとしない。

## 保持する残件

これは全P3領域の分離、任意core間の機能互換、独立classloader、全旧bean書込み/スレッド境界を完了させる変更ではない。限定的な任務条件や出撃途中の未観測区間は既存backlogを維持する。Issues機能無効の登録原稿も登録済みとは扱わない。
