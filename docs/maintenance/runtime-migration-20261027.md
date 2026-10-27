# 担当B：新規コピーへの移行・検証起動・一式復元

確認日：2026-10-27（日本時間）。実装は `parallel/runtime-migration-20261027`。

## 依存関係と完了の区別

開始時のmasterは `06be3c9dd4137efbffa5d4f91800881dbc07a5ff`（PR50まで）。
PR48は未統合で、本文の古いheadではなく、レビュー修正済みの
`2cc9281a25b417ea9178a99f52810c6ce3bfdd4a` を担当Bブランチの親とした。
PRはPR48のブランチをbaseとする。PR48統合後に統合担当がmasterへ付け替え、
依存・競合と最終headのCIを確認する。masterへの直接push・マージ・公開は行わない。
PR48の `migration_preflight.py`、`maintenance_plan.py`、`maintenance_workflow.py` と
同PRの試験は無変更。既存preflightを呼び、担当Bの新しいplanで検証済みsnapshotの
primary／backupを再列挙する。古いsnapshotの不完全なpath_reviewだけで判断しない。

PR49 `716c341599a79cd02f2be27e8d2fb32672350a16` の残件管理コードも確認した。
同PRは今回の機能のコード依存ではない。現在のcheckerは初期19件を固定し、
`verified_complete=true` を拒否するため、本実装から台帳を勝手に完了へ変更しない。
担当Bの証拠はR11-01の**下記対応版に限定した範囲**とR11-02に対応する。
R11-18のMint・本人データ確認は未完了のまま。

「コード実装」「最終headのCI合格」「master統合」「Mint確認」「本番適用」は別の状態。
最終head、CI実行ID、検出・成功・失敗・エラー・スキップの件数はPR本文に記録する。

## 対応範囲

| 対象 | 対応内容 |
|---|---|
| snapshot | 既存 `logbook-runtime-snapshot-v1` のハッシュ固定・全バイト検証を利用 |
| パス移行 | 現行AppConfigの12項目をコピー側だけで再設定。primaryとbackupの両方を処理 |
| 外部データ | ユーザーが指定した**元設定値→ローカル実パス**だけを別領域へコピー |
| managed import | 新規の `logbook-managed-split-v1` storeにのみ明示取込み |
| `legacy-config-v1` | 既知AppConfig・受諾履歴、到達journalなし |
| `arrival-journal-v1` | 上記に加えて既存 `sortie-arrivals-v1`、公開JSONとpendingの双方 |
| 既知版A | R10 master `a094771d73ce420f2bd2ddf237e0c69cf2005067` |
| 既知版B | PR50後master `06be3c9dd4137efbffa5d4f91800881dbc07a5ff` |

A/Bは実在する別のソースコミット。AppConfig・Config・到達記録・受諾履歴の形式は
この2版で同じであり、未知形式を推測して変換しない。専用CIは固定コミットを別々に
checkoutして未変更のアプリJARをビルドする。検証はAの読取り／合成データ作成から、
Bによる承認済み変更、起動前一式の新規復元、Aによる再読取りまでを対象とする。
同一JARへ世代ラベルを追加する従来のfixture試験とは区別する。
両コミットの表示バージョンは `25.0.10`。異なる公開リリース番号間の互換性を
証明したとは扱わない。パッケージ時刻等でJAR全体のハッシュは変わり得るため、
CIは固定source commit／treeと実クラスを検証し、各ビルドのハッシュを記録する。

これは任意の歴史的JAR、将来版、未知のJSON形式間の変換保証ではない。
全ファイルをバイト保持しても、opaqueなcustomquest、画像、第三者プラグイン等の
意味・互換性を自動認定したことにはならない。

## 移行の状態遷移

1. **plan**：snapshotの整合性を検証し、primary／backup、暗黙の既定値、必要な外部入力を列挙する。
2. **copy**：計画のハッシュと現在の入力から計画を再検証し、新しい出力先へコピーする。
3. **approve-validation**：未解決事項ゼロ、コピーの内容・設定型・原本baselineを検証し、実行するコマンドと入力ハッシュを固定する。
4. **launch-validation**：別の明示確認を要求し、固定したコマンドを検証用コピーで1回だけ実行する。
5. **restore / restore-store**：起動前のsnapshotから、さらに別の新規領域へ復元する。

コピーや承認だけではアプリを起動しない。各receiptは `launch_safe=false`、
`native_mint_tested=false`、`ready_for_production=false` を維持する。
検証用起動が成功しても、それらを本番運用の許可へ変更しない。

## パスと設定の扱い

12項目は `reportPath`、`performanceDir`、`pluginsDir`、`resourcesDir`、
`battleLogDir`、`captureDir`、`storeApiStart2Dir`、`missionSoundDir`、
`ndockSoundDir`、`alertSoundDir`、`defaultNotifySound`、`ffmpegPath`。

- 相対／絶対／別OSのパスを区別する。未選択の外部参照を勝手にstat・読取り・コピーしない。
- 外部入力の同一パスと、明示選択した親ディレクトリ配下の重複入力はまとめて1回コピーする。
- コピー後はruntime内の相対パスを使うため、managed storeへの取込みや新規復元で再配置できる。
- 設定で指す内部ディレクトリがsnapshotにない場合は、新しい空ディレクトリとして明示記録する。
  **明示選択した実データ**がsnapshotにない、または変わっている場合は空として扱わず、fresh snapshotを要求する。
- primary欠落・空・不正JSONで有効なbackupがある場合は、その設定をコピー側で使用する。
  元のprimary／backup全バイトは `original-config/` に残す。
- コピー先でも設定を一度コピーして上書きする方式は使わない。原本を別領域に保存し、再設定済みファイルを新規作成する。
- AppConfigの現行79プロパティを名前・型で検査する。パス以外の型不正でJavaが既定値へ戻るケースも、承認前に拒否する。
- プラグイン配下、起動スクリプト拡張子、実行権限、shebang、ffmpeg実行ファイルを未確認artifactとして列挙する。
  `usePlugin=false` だけでは確認済みへ変更しない。
- `reviewed_artifacts` は、実際に確認したartifactのruntime相対パスとSHA256の組。
  これは呼出者の内容確認記録であり、スクリプトの静的解析や全プラグインの安全性保証ではない。
  検出した一覧を無条件に全部承認する用途に使わない。
- 未知のパス項目、非空の未検証実行引数、未選択外部パス、未知形式は保留／拒否する。

## 保全と失敗時の動作

すべて新規出力。既存出力、元／snapshot／選択入力との親子重複、`..`、先頭`//`の
別名、シンボリックリンク、特殊ファイル、別デバイスの部分木、容量・件数・深さ超過を拒否する。
選択入力はコピー前後に全inventoryを比較し、コピー中の変更を検出する。
ハードリンクはコピー先で独立したファイルにする。

途中失敗ではintentと途中コピーを残し、完了receiptを作らない。自動削除・自動再開・
上書き復元・削除同期を行わない。POSIX mode・mtime以外のACL、xattr、所有者、
sparse配置、ハードリンク構造の完全保存は既存snapshotと同様に範囲外。

旧snapshot以降に元環境が進んでいても、復元のために元を巻き戻す必要はない。
実行時の**現在の原本**を別baselineとしてコピー前後・検証起動前後に確認し、
旧snapshotの内容は新規コピーへ復元する。`original_matches_snapshot` がfalseでも、
現在の原本を変更してよいという意味ではない。

Linuxの検証起動では、元・snapshot・選択入力・原本設定archive・宣言した実行入力を
inotifyとハッシュで監視する。一時的に書いて元に戻す操作も失敗にする。
監視不能、監視喪失、入力変化、timeout、非ゼロ終了は成功扱いしない。
stdout／stderr／結果は検証コピーの新しい `validation-runs/<id>/` に保持する。
ログ出力先がリンクへ差し替えられていれば、出力作成・プロセス生成前に拒否する。

**監視はOSの書込み隔離ではない。** 任意の悪意あるコード、外部へdaemon化する
プログラム、同一ユーザーによる競合を完全封じ込めする機能ではない。
今回のCIで実行するのはソースを確認した合成fixtureと既知版JARだけ。
Javaはコピー側のuser.home・一時ディレクトリを使用し、自動更新を無効化する。
元アプリ・ユーザーのプラグイン・auto.shを実行するものではない。

## managed storeへの取込みと復元

`runtime_managed_import.py` は既存managerのinitialize／stage／activate契約を呼ぶ。
共通managerの形式、PortArrivalStoreの形式、UI、定義JSONを変更しない。
指定した旧・新bundle、全runtime、到達記録・pending、受諾履歴、customquest、画像、
プラグイン、既存バックアップを保持する。新規storeが作るhomeとの重複も、削除なしで扱う。

設定・受諾履歴・到達記録の既知構造を検査し、未知format、型不正、競合する記録ID、
不正な日付・配置等を取込み前に拒否する。原本は残る。

selectorのrollbackはアプリだけを戻し、変更後runtimeを残す既存機能である。
今回の `restore-store` はそれとは別に、**起動前store全体のsnapshot**から、
旧selector・保持bundle・旧runtimeを新しい領域へまとめて復元する。
変更済みstore、旧版、新版、snapshot、失敗証跡は保持する。

移行済みruntimeだけを再snapshotすると、snapshotには直前のコピー元のみが記録される。
そのため取込み時は `protected_roots`／`--protected-root` で、それ以前の原本・外部入力を引き継ぐ。
whole-store復元は、検証済みのimport receipt／intentからこの保護境界を自動継承する。
保護パスの内容を自動収集する機能ではない。

## CLIの使い方（合成fixtureで確認してから利用）

設定値と実パスの対応、artifactの内容確認、実行argvは、それぞれJSONファイルに用意する。
Windows等の旧パスもJSONのキーにできる。ハッシュは各作成時の出力から固定する。
以下の変数はレビュー済みの新規出力先・対応ファイルを指すものとする。

```bash
# 読取りのみ。未選択の外部パスは一覧に残り、承認は通らない。
python3 scripts/runtime_migration.py plan \
  --snapshot "$SNAPSHOT" --manifest-sha256 "$SNAPSHOT_SHA256" \
  --out "$NEW_COPY" --selected-paths "$SELECTED_PATHS_JSON" \
  --reviewed-artifacts "$REVIEWED_ARTIFACTS_JSON" > "$NEW_PLAN_JSON"

# 計画のSHAを確認後、停止した入力から新規コピーを作る。ここでは起動しない。
python3 scripts/runtime_migration.py copy \
  --plan "$NEW_PLAN_JSON" --plan-sha256 "$PLAN_SHA256" --confirm-copy

# argv中の {runtime} / {home} / {tmp} はコピー先の専用領域に置換する。
python3 scripts/runtime_migration.py approve-validation \
  --copy "$NEW_COPY" --receipt-sha256 "$COPY_RECEIPT_SHA256" \
  --command-json "$REVIEWED_ARGV_JSON" --command-inputs "$COMMAND_INPUTS_JSON" --confirm
python3 scripts/runtime_migration.py launch-validation \
  --copy "$NEW_COPY" --approval-sha256 "$APPROVAL_SHA256" --confirm-launch

# managed importには、起動前の移行済みruntimeの別snapshotを渡す。
python3 scripts/runtime_managed_import.py import \
  --snapshot "$PORTABLE_SNAPSHOT" --manifest-sha256 "$PORTABLE_SNAPSHOT_SHA256" \
  --out "$NEW_STORE" --profile arrival-journal-v1 \
  --bundle "$BUNDLE_A" "$BUNDLE_A_SHA256" --bundle "$BUNDLE_B" "$BUNDLE_B_SHA256" \
  --selected-bundle-sha256 "$BUNDLE_A_SHA256" \
  --protected-root "$ORIGINAL_ROOT" --protected-root "$EXTERNAL_SOURCE" --confirm-import

# 起動前store全体のsnapshotから、旧アプリ＋旧データを新しい場所へ復元する。
python3 scripts/runtime_managed_import.py restore-store \
  --snapshot "$WHOLE_STORE_SNAPSHOT" --manifest-sha256 "$WHOLE_STORE_SHA256" \
  --out "$NEW_RECOVERY" --profile arrival-journal-v1 --confirm-restore
```

## 検証と継続する残件

実装前に失敗を再現したPython試験を作成し、修正後に対象試験を再実行した。
独立したレビューで、backup漏れ、`//`による重複判定回避、ログ出力先リンク、
未検証設定型、旧snapshotと現在原本の取り違え、明示入力の空コピー、namespace衝突を検証した。

既存CIのJava21／25・GUI・全Python試験を維持する。固定A/B版の専用CIでは、
実JARによる起動・変更・旧版再読取りをスキップなしで要求する。
実Java試験はConfig、AppQuestDuration、PortArrivalStoreを使用し、到達3件／受諾対象2件を
変更後4件／1件にしてから、全体復元後3件／2件へ戻ることを検証する。
画像・customquest・pluginの保持、原本と外部入力の不変も照合する。

未完了：Mint実機・本人データでの確認、本番適用、未知スキーマの変換、
任意の第三者plugin／起動スクリプトの解析、歴史的な全アプリ版との互換性、
ACL等の完全保存、PR49の将来の完了表現。本実装を理由に既存残件を消さない。
