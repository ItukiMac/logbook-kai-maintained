# logbook-kai2

艦これのプレイ情報を記録・表示し、任務の完了管理と情報の共有・出力を行うデスクトップアプリです。

## 主な機能

- 艦娘・艦隊、装備・基地航空隊の一覧と状態表示
- 任務の観測、報酬受領による完了記録、手動補正と履歴管理
- 戦闘・遠征・開発などの記録、資材推移、統計、計算機
- ブラウザ接続と、全情報または区分別のJSON・CSV出力

## 導入

Java 21を基準に開発・検証しています。Windows、macOS、Linuxが対象ですが、各OSでの実機確認とCIの検証範囲は異なります。

現在の変更は開発候補です。公開リリースとして認定するまでは、検証用配布物を実環境へ上書きしないでください。

- [導入と接続](docs/user/setup.md)
- [任務管理・共有と出力](docs/user/quests-and-sharing.md)
- [データの引き継ぎ方針](docs/user/data-import.md)
- [リリース一覧](https://github.com/ItukiMac/logbook-kai-maintained/releases)

## 構成

本体と8つの機能JARで構成します。各JARは同じ配布世代の組み合わせで使用します。

| モジュール | 機能 |
| --- | --- |
| core | 起動、通信解析、共通データ、モジュール管理 |
| ships | 艦娘・艦隊 |
| equipment | 装備・基地航空隊 |
| quests | 任務・完了管理 |
| records | 各種記録 |
| charts | グラフ・統計 |
| calculators | 計算機 |
| bridge | ブラウザ接続 |
| sharing | 情報共有・出力 |

## ライセンス

[MIT](LICENSE)。継承コード・同梱データ・ライブラリの表示は[第三者情報](THIRD_PARTY_NOTICES.md)を参照してください。

開発への参加は[CONTRIBUTING.md](CONTRIBUTING.md)を参照してください。
