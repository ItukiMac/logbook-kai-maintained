# 担当E：機能JARの独立更新と起動前互換検査

基点は `06be3c9dd4137efbffa5d4f91800881dbc07a5ff`。2026-10-27にGitHubのmasterと
open PRを読み直した。PR48の最新headは `2cc9281a25b417ea9178a99f52810c6ce3bfdd4a`、
PR49は `716c341599a79cd02f2be27e8d2fb32672350a16`。実コードとレビューを確認し、
両PRの保守・移行・完了台帳は変更していない。

## 実装した更新単位

配布物はcore＋3機能JAR。登録済みFeature SPIは7件、FXML画面は経験値編集と
今回の基地航空隊・アイテム一覧を含め10件。単一更新の単位は1つの機能JARであり、
calculator内の4機能が別々のJARになったという意味ではない。

| module ID | 初期版 | 主な所有物 |
|---|---|---|
| calculators | 1.0.0 | 経験値、海域経験値編集、遠征確認、確率、ラスダン、専用行モデル・確率計算 |
| charts | 1.0.0 | 資材・経験値チャート、専用行モデル |
| status | 1.0.0 | 入渠候補、基地航空隊、アイテム一覧、専用行モデル |

基地・アイテムは従来メニューの同じFXML参照から機能JARの実画面を開く。
既存メニューと重複させず、今回は7件のSPI登録数を維持する。
任務編集UI、到達観測、任務定義ルールには変更を加えていない。

## 公開契約と互換の範囲

`FeatureModule` に、旧実装とのbinary互換を保つdefault `descriptor()` を追加した。
module ID、数値版 `major.minor.patch`、必要なhost API、依存moduleの版範囲を宣言する。
版の各成分は0〜2147483647、先頭0は禁止。依存範囲は下限を含み上限を含まない。
旧FeatureModule実装は単体fatJAR経路では使用できるが、版宣言のない実装を管理配布へ
混ぜることは拒否する。従来のKLB/KQS向けSPIは変更していない。

GUIのpackage-private呼出しは公開・版付きの `FeatureUiV1` を通す。
新規共通APIとcore側bridgeの2ファイルは独立した差分として提出する。
既存TableTool、InternalFXMLLoader、WindowController、MainMenuController自体は変更しない。
詳しい所有境界とP3残件は `feature-ui-v1-boundaries.md` に記載した。

**保証する組合せはhost API 1かつ同一core JARのSHA256に限る。** 公開された既存bean、
計算・ログ読取への依存は残るため、API番号だけで異なるcore公開版の互換を宣言しない。
製品は共有classloaderを使う。独立classloader、ホットリロード、第三者プラグイン隔離、
任務・艦娘装備・記録を含むP3全体の分離は未完了のまま保持する。

## 二段階の起動前検査

配布manifestは `logbook-feature-distribution-v2`。各moduleのID/版/host API/host SHA/
依存範囲/provider/feature IDs/JARパス/JAR SHAを保存する。module JAR内にも
`META-INF/logbook/feature.json` を収録し、外側manifestと一致させる。
coreには `META-INF/logbook/host-api.json` を収録する。

Pythonの `verify()` は、必須module・feature・controller・FXML・専用モデルの欠損、
ID/JSONキー/依存の重複、未知API、循環依存、不正な版と範囲、非互換の組合せ、
別core、ハッシュ不一致、パス逸脱、symlink、危険/重複ZIPを拒否する。
JAR間のclass/FXML所有重複も拒否する。機能JARのMulti-Releaseは未対応として拒否し、
coreの第三者Multi-Release classは同じ論理名の所有を確認する。
manifestのClass-Pathは空や折返し、CR改行、Unicode空白を含め拒否し、ビルダーは
新しいcoreコピーから元fatJARの暗黙Class-Pathを取り除く。

続いて通常Launcherより前に、固定4JARだけをclasspathにした別JVMで
`logbook.core.FeatureBundlePreflight` を実行する。各provider自身のJARからmetadataを読み、
実descriptor・feature IDs・実core SHAと照合する。Config、LogBookCoreServices、
外部plugins、GUI、proxyは初期化しない。終了code 0と単独成功markerの両方がある場合だけ
同じclasspathと保持中のロックで通常Launcherを開始する。
自己申告JSONだけが整合し、実providerがAPI2を要求する候補もここで拒否する。

ハッシュは確認した配布物との同一性であり発行者の署名認証ではない。
非協調的な同一ユーザーによる実行中のファイル改変を完全に防ぐ仕組みではない。

## 単一module更新と復帰

`stage-feature` は確認済み完全配布物と現在selectorの期待SHAを入力にする。
候補一式を検証したうえで、対象の1JARだけを取り、core・残る2JAR・検証scriptは
現版からコピーする。合成した全依存関係を再検証し、新しいmanifest SHAを版のIDとする。
同じmodule版番号なのにbytesが変わる候補は拒否する。

元配布、現版、旧版を上書きしない。新しい `versions/<SHA>/` を準備するだけで、
stage時点ではselectorもruntimeも変わらない。`composition` に元一式・候補一式のSHA、
候補source revision、更新moduleを記録する。合成配布の `source_revision` はcoreの由来を示し、
全JARがその1commitから生成されたという意味にはしない。

activateは既存のCAS・排他ロック・履歴先行保存・selector原子置換を使う。
rollbackは検証した直前の一式を選び直す。途中コピーのpending、失敗selector一時ファイル、
履歴は保持する。設定・ユーザーデータのschemaを巻き戻す操作ではない。

```text
python3 scripts/feature_bundle_manager.py --store /new/test-store stage-feature --bundle /verified/candidate --expected-sha256 <候補manifestのSHA256> --module calculators --expected-current <現在selectorのSHA256>
python3 scripts/feature_bundle_manager.py --store /new/test-store activate --version <stage-featureが返した新しいSHA256> --expected-current <現在selectorのSHA256>
python3 scripts/feature_bundle_manager.py --store /new/test-store launch
python3 scripts/feature_bundle_manager.py --store /new/test-store rollback --expected-current <更新後selectorのSHA256>
```

これは開発用の新規・隔離storeの操作例。現用環境へ自動取込みや本番切替はしない。
版契約のない旧v1配布を新ツールで黙って起動可能に格上げしない。旧manifest/JARは保持し、
v2配布を別に作る。既存v1 storeのin-place変換や、v2からv1を新ツールで起動する経路は未対応。
旧環境・旧実行手順・バックアップの削除を意味しない。

## 並行PRとの統合用差分

PR #51の最新head `e6b9ef2711c1445d71118e660a60313d3e727ee3` を確認した。
本体の移行機能とは直接競合しないが、旧v1形式の合成bundleと、世代印追加後に
host SHAを更新していないテスト用bundleはv2検査に拒否される。
`integration/runtime-migration-feature-v2.patch` は、そのテスト2ファイルだけを直す
統合担当向け差分である。Bの本体・ブランチへは適用していない。

旧fixtureの拒否を先に再現し、B＋Eを置いた別cloneでmanaged import 38件・
runtime migration 31件・PR #48 preflight 7件の計76件が成功（失敗0、スキップ0）。
世代印helperのA/B合成bundle検査と元fatJAR不変も成功し、上記B headへの
patch適用検査を別indexで通した。patch SHA256は
`a3efb870d743c7329eb6cd5313ba18d36e1a61ce364b1d0808ec0394e3d3d305`。

歴史版fatJARをv2の保存用bundleへ包んでも、旧providerに新しい起動契約が備わる
わけではない。このpatchでは保存・復元の検証と旧fatJARの直接実行を区別し、
`split_bundle_launch_validated=false` を証跡へ出す。Bの歴史A/B・Launcherを含む
Java5件はここでは未実行であり、B＋E統合後の既存専用CIで確認する残件とする。
PR #52の到達観測・復旧head `40c0ea7e9022b8cf1559a0eb34bba6b45e6f47f9` も
確認し、Eとは直接の変更ファイル競合がない。いずれもEから変更・統合しない。

## 試験とCIで確認する証拠

実装前に、版情報欠損・重複JSONキーの見逃し、managed未版付providerの受入れ、
GUIからの非公開host呼出しを失敗として再現した。
Class-PathのCR/NBSPとMulti-Releaseの実JDK解釈も再現したうえで拒否試験を加えた。

- Javaの版/依存/embedded契約・旧SPI binary・別JVM preflight試験31件。
- GUI境界試験5件。別classloaderで実controllerを読み、非公開呼出しに依存しないことを確認。
- Pythonの専用拒否試験47件と、管理・単一module更新・既存分割試験。
- 実小機能A=1.0.0/入力×2、B=1.1.0/入力×3、C=API2、E=JSON API1/実provider API2を別コンパイル。
  実Launcherとメニューから画面を開き、計算・保存結果10→15→15→10を検査する。
  C/Eの拒否、コピー途中失敗、selector置換失敗、一式Aへの復帰、元/旧版/設定/履歴/plugin保持も検査する。
- 基地・アイテム実画面は分割JARだけで実行し、制空42/半径5/しきい値126、所持12、表示切替、
  設定2件の保存・再読込、合成履歴保持を検査する。
- 従来の同一コード世代印A→B→A試験も保持し、追加されたhost SHA pinに合わせて合成fixtureを更新。

実行中の大きいfixtureは新規TempDirに保持し、完成したログ/JSONはtargetへ別名保存する。
正常系だけでなく失敗証跡も消さない。テスト用hookはsrc/testにあり製品JARへ含めない。
Java21/25と全GUIは既存R3 CI、旧KQS/新KQSは既存plugin互換CIで実行される。
KLBの既存CIは旧sourceの現host向けコンパイル確認で、本人の実配備binary/実データ動作を
確認したことにはしない。既存CI、pom.xml、verify_r3.shへの変更はない。

ローカル統合Java21/25は各525検出/524成功/失敗0/スキップ1（意図的な実公開通信試験）。
Java25では既存verify_r3.shも変更せず完走した。追加前の全Pythonはscripts215件＋tools25件が
両Java環境で成功し、さらに3moduleすべての単独stageで未選択候補が混入しない試験を加えた。
最終PRの正確なheadに対するJava21/25・GUI・KLB/KQS・全Python結果とrun IDはPRへ記録する。
ソース実装、PRのCI合格、master統合、Mint確認、本番適用を別々に扱う。
Mint/OS/現用JAR/auto.sh/本人設定・履歴・plugin・バックアップへの接続や変更、Release/タグ、
master直接push、自動mergeは実施しない。
