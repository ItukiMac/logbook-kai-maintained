> Integration amendment (2026-10-27): matching fleet/cell/timestamp after a process restart does not establish an uninterrupted sortie. The integration correction retains all journal bytes, duplicate protection and confirmed retreat evidence, but refuses credit for an ambiguous resumed route (`RESTART_CONTINUITY_UNPROVEN`). A newly observed port boundary and departure permits tracking again. Earlier descriptions of matching-observation automatic recovery below are historical design/test records, not the current crediting policy. See `parallel-integration-20261027.md`.

# 担当D: 到達追跡の再照合・退避後編成（2026-10-27）

## 基点と分担

開始時にGitHubのmasterとopen PRを再読し、masterは `06be3c9dd4137efbffa5d4f91800881dbc07a5ff` だった。
独立clone/ブランチ `parallel/arrival-recovery-20261027` で実装した。
PR48の最新headは `2cc9281a25b417ea9178a99f52810c6ce3bfdd4a`、PR49は `716c341599a79cd02f2be27e8d2fb32672350a16`。
PR48本文の旧headを採用せず、差分・レビューを確認した。両PRのPython/台帳/CIは変更しない。

現用Mint、OS、現用JAR、auto.sh、本人の設定/履歴/plugins/バックアップへの接続はない。
以下の試験データはすべて合成、変更先は新規TempDir/独立clone。通信再現試験を実艦これ通信の確認とは扱わない。
UI、季節JSON、905/912/945の定義、snapshotツール、pom.xml、既存CI、verify_r3.shは変更しない。

## 実装した観測経路

既存 `APIListener` の同期ハンドラー成功後の入口を継続利用する。JSON復号失敗の既存invalidate入口も永続失効へ接続する。

- 出撃開始、進撃、退避候補、確定退避、到達消費、母港/ログイン境界、無効化を独立した追記記録に保存。
- 保存済み追跡をそのまま有効にしない。再起動後は、実際に受信した `ship_deck` の個体ID/マスターID/出撃艦隊/元の順序を確認し、次の `map/next` の `api_from_no` が保存セルへ接続したときだけ再照合する。
- 保存された `AppCondition.mapStart/deckId` が整合し、退避集合が完全一致していることも必要。キャッシュだけ、前セルなし、欠落した艦、異なる艦隊/マスター、別海域は復旧しない。
- 捕捉時刻 `x-koukainissikai-requestat` は正のms値で、観測時計より未来/24時間超過を拒否。復旧には出撃以降の各観測に捕捉時刻があり、fresh fleetと次mapの捕捉時刻が保存観測より新しいことを要求する。欠落した時刻を現在時刻で補わない。
- 開始後だけでなく最終観測後の時計逆行、24時間超過、JST日付変更、05:00の日次境界で無効化する。母港/ログイン、編成変更、一部ハンドラー失敗、不正JSONも永続失効。
- map値、成功値、退避indexは整数として厳密に検査。`9.5 -> 9`、`1.5 -> 1`、欠落primitiveの既定値補完で証拠を作らない。requestに対応値が存在する場合は矛盾も拒否する。

### 出撃識別と未観測通信の限界

出撃UUIDはアプリのローカル識別子であり、ゲームサーバーが署名した出撃IDではない。
今回の再照合が確認するのは、新しい捕捉時刻、同じ個体/元位置/マスター、同じ出撃状態/退避集合、前セル接続である。
**停止中に母港へ帰り、同じ艦隊で別出撃して同じセルまで進むような、観測されなかった全往復をこれらの値だけで識別することはできない。**
捕捉元の永続的なセッションID/連番による全区間の連続性保証は提供されていないため、同一出撃の完全な証明・全欠落検出とは呼ばない。
既存捕捉キューの上流drop/並列到着順までこの変更が解決したとはしない。これらの実通信検証と捕捉元連続性は残件であり、R11全体を完了へ変更しない。

捕捉時刻付き開始の後で捕捉時刻が欠落した進撃/退避イベントは、以前のGOALの再受信か識別できないため失効する。
捕捉時刻なしの開始は新規履歴がない場合の連続観測だけに限る。既存履歴がある状態で捕捉時刻が欠けた開始は、正規の新出撃と再送を識別できないため不加算にする。
連合第1・第2艦隊は従来どおり対象外。連合結成中でも独立した第3・第4艦隊は対象として検査する。

## 二重計上・破損時の振る舞い

開始イベントと進撃イベントの最小フィールド/捕捉時刻をハッシュ化して保持し、同じstartを受けてもUUIDを作り直さない。
観測済みの母港/ログイン境界がない別startは新しい出撃へ切り替えず無効化する。
到達イベントを確認したら、**GOAL消費記録 → 編成追加証拠 → 従来v1到達記録** の順で書く。
公開途中で失敗しても消費済みIDから新しい到達を作らず、再起動・再送による二重加算を防ぐ。
消費済みだが到達本体の公開に失敗した場合は未計上が残る。保存済みGOALだけから自動で再発行しない。
過去B/D戦闘や母港帰還から到達を補完する経路は追加していない。

ジャーナルは日付ごとに連番/前ハッシュ/SHA256を検査し、ファイルロックと直前entryの比較により競合する追記を拒否する。
欠番、途中書込み、重複キー、後続JSON、未知形式/field、不正型、矛盾する状態遷移、symlink、過大記録は不加算。
失敗したバイトは削除・修復・上書きせず、その日付の破損を自動で回避しない。
各entry 16KiB、1日20,000件、8,000日を上限とする。古い日付を自動削除しない。
ハッシュは整合性検査であり、同一ユーザーによる意図的な改変の真正性証明ではない。
保留理由は固定reason codeとしてログへ出し、到達状態の試験でも確認する。生HTTPや認証情報は追加記録へ保存しない。

## 確定できた退避だけを扱う

対応する経路は通常艦隊の `api_req_sortie/battleresult` の退避候補を新しく観測し、続く `api_req_sortie/goback_port` が成功した場合。
既存ハンドラーが使うsingle `api_escape_idx` と任意のsingle `api_tow_idx` に限定する。

候補の全index、同じセルの確認済み戦闘、同じ艦隊番号、個体ID/マスターID/全元位置を照合する。
退避後はホストのescape集合が「従来の確定退避 + 今回の対象/護衛」に完全一致した場合だけ採用する。
旧戦闘の流用、確認済み戦闘の差替え、範囲外/複数index、不明個体、位置の変更、未観測の退避集合追加、全艦退避は拒否。
未確定の候補があるまま進撃した場合も、辞退と退避通知欠落を推測で区別せず保留する。
連合退避endpointや確定不能な形は既存の安全側失効を維持する。

`PortArrivalFleet` は元の1-based slot・個体ID・マスターID・退避有無を全位置について保持する。
残存艦を前へ詰めない。従来v1のmaster列は出撃時の意味のまま維持し、追加証拠なしの旧 `noRetreat=false` 記録から残存編成を推測しない。
`filter.noRetreat=true` は確定退避があっても必ず不成立のまま。

## C向けの追加契約と共有差分

共有変更は独立したコミットへ分ける。

1. `AppQuestCondition.FleetCondition.testObservedSlots(List<ShipMst>, Set<Integer>)` を追加。
   Setは1-basedの証明済み空き位置。null位置との完全一致、1〜7位置、最低1隻生存を検査する。
   既存の `test(List)` は不明nullを拒否する従来契約のまま。
2. `PortArrivalRuntime.readFleet(PortArrival)` は `Optional<PortArrivalFleet>`。
   不存在はempty、破損/到達metadata不一致はIOException。`PortArrivalFleet.matches` が存命艦のマスターを解決し、元位置を保って追加述語へ渡す。
3. `QuestCollect` はnoRetreat=trueの従来経路を保ち、falseかつfleet条件がある場合だけ追加証拠を使う。
   `filter.noRetreat=true`、海域、艦隊番号の拒否はその手前に残す。不明は不加算、破損は既存の「到達記録を確認できません」へ接続する。

## B向けの保存パス・移行保持要件

| パス（アプリ作業ディレクトリから） | 内容と扱い |
|---|---|
| `sortie-arrivals-v1/YYYY-MM/<UUID>.json` | 従来の到達記録。形式・読込・既存バイトを維持 |
| `sortie-arrivals-v1/YYYY-MM/.<UUID>-<UUID>.pending` | 従来の公開前証跡。削除しない |
| `sortie-arrivals-v1/tracking-v1/YYYY-MM-DD/NNNNNNNN.json` | 新しい出撃追跡/失効/消費の追記記録 |
| `sortie-arrivals-v1/tracking-v1/.lock` | 書込協調用の空ファイル |
| `sortie-arrivals-v1/fleet-evidence-v1/YYYY-MM/<UUID>.json` | 新しい元位置/個体ID/確定退避の追加証拠 |
| `sortie-arrivals-v1/fleet-evidence-v1/YYYY-MM/.<UUID>-<UUID>.pending` | 追加証拠の公開前バイト。削除しない |

停止したアプリの**ルート全体**を新規先へコピーするR10方式なら、上記の全ファイルと失敗途中ファイルも保持される。
単にYYYY-MMだけをコピーする実装や、pending/失敗証跡を除く同期へ変更しない。コピー先から元を上書き・削除しない。
既存v1 readerは新namespaceを列挙しない。これは読込互換の確認であり、新形式を旧版へ無条件配布して起動してよい根拠ではない。
旧版へ新しい追跡/追加証拠を適用する機能は実装していない。旧版への復帰・異版移行時は出撃を継続中として自動復旧させず、保存された失敗証跡を保持して別途起動条件を審査する。
このPRではBのsnapshot/migrationコードを変更しない。

## 試験の証拠と残件

変更前の実測:

- 復旧/二重計上/時計/日付/退避/編成変更の6件: 6検出、6失敗、error0、skip0。
- 観測slot述語の12件: 1成功、11失敗、error0、skip0（追加method未実装）。
- API整数丸め/矛盾request/必須追跡field欠落の4件: 4検出、4失敗、error0、skip0。
- 追加証拠の30件: 26成功、4失敗、error0、skip0（欠落/null/文字列/小数の既定変換）。修正は新しい証拠用mapperに限定。
- 再開semantic検査の8件: 4成功、4失敗、error0、skip0。同じ開始key/UUIDの再使用、未来payload、捕捉時刻を削った古いGOALの新出撃への誤加算を再現して修正。
- journalの初回は未実装classのコンパイルエラー、small-longのwire型正規化は1件の実行errorを再現して修正。これを成功した試験と数えない。

Java21/25の既存R3検証ゲート、最終PR headのGitHub Actions実行ID/件数はPR本文で記録する。
単一JVM内のruntime再生成に加え、出撃保存・復旧・再送を**別の3 JVM**で実行する回帰も追加した。
実旧masterのPortArrivalStoreを専用classloaderへロードし、新namespaceを無視しながら2到達と元v1/pending4ファイルの全バイトが保持されることも確認した。

完了区分は「実装」「PRのCI」「統合」「Mint確認」「本番適用」を分ける。
実艦これ通信の順序/ヘッダ/前セル/退避形、全区間の捕捉連続性、Mint/IME/Chrome/長時間、新旧実データ移行、季節版/敵分類/全改装/受諾/前提/リセット等の既存残件は消さない。
masterへ直接push/mergeせず、Release/タグ/本番切替は実施しない。
