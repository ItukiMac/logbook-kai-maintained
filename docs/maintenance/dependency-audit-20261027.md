# 依存ライブラリの実測監査と互換更新 — 2026-10-27

## 根拠と判定範囲

基点は `7bb834402d017bb61ffeca6fa7ad133793f1c26a`（PR #55統合済み）。
2026-10-27T06:23:48ZにMaven Centralの配布メタデータを実取得した。
`pom.xml`の直接依存だけでなく、effective POMのビルドプラグイン、実際の依存解決ツリー、注釈処理器、別座標のJackson3を合わせて57項目。
全57項目の取得に成功。候補版の実在と元版の掲載も確認した。

- 監査run: https://github.com/ItukiMac/logbook-kai-maintained/actions/runs/40460817992
- 成果物ID: `12714078335` (`pinned-dependency-audit`)
- ZIP SHA256: `7a90e131eee6419aa9358c25613dcd2a0520584f5fb170a8dc4b713f7996c362`
- ZIPにはeffective-pom、dependencies.json、全metadata XML、各raw SHA256付きJSON、元ソースを保持。
- 元XML URL: `https://repo.maven.apache.org/maven2/{groupIdを/区切り}/{artifactId}/maven-metadata.xml`
- SHA256とZIP CRCを取得後に再検査済み。最新の判定はこの取得時点に限定する。

数字の安定版を比較し、EA/RC/beta/SNAPSHOTは採用しない。バージョン大小はAPI互換性や脆弱性診断の代わりではない。
ライブラリ内部の全ビルド依存・OSライブラリ・JDKパッチ版・GitHub Actionsの最新版まで認証する監査ではない。

## 今回修正する7座標

| 対象 | 元 | 更新先 | 範囲 |
| --- | --- | --- | --- |
| ControlsFX | 11.2.5 | 11.2.6 | アプリ実行時 |
| Maven Surefire | 3.6.0 | 3.6.1 | 試験ランナー |
| SLF4J API | 2.0.17 | 2.0.20 | 間接・実行時 |
| JSpecify | 1.0.0 | 1.0.1 | 間接・試験 |
| Byte Buddy | 1.17.7 | 1.18.16 | 間接・試験 |
| Byte Buddy Agent | 1.17.7 | 1.18.16 | 間接・試験 |
| Objenesis | 3.3 | 3.6 | 間接・試験 |

間接依存はdependencyManagementで明示し、元のscopeを維持する。新しい実行時依存を追加する目的ではない。
`.github/workflows/dependency-resolution.yml`はJava21/25それぞれで実解決版・scope・Surefire版を検査し、宣言だけの更新を合格としない。
実GUI・通常ランチャー・旧新KQS/KLB・移行復元・分割版・季節/退避結合の既存CIも必要。最終の実行番号・合否・統合SHAは更新PRに記録し、監査成功だけで動作確認済みとはしない。

## 最新メジャーへは移行していない2系統

- JavaFXは21系の最新 `21.0.13` を維持。全系統の最新は `27.0.1`。JavaFX27はJDK25以上を要求するため、Java21対応を保持したまま単純置換はできない。参照: https://openjfx.io/highlights/27/
- Jacksonは2系の最新 `2.22.4` を維持。新メジャーの `tools.jackson.core` は `3.2.4`。Maven座標・Javaパッケージ・APIが変わるため、保存設定、JSONの厳格検証、プラグイン公開型を含む別の移行が必要。参照: https://github.com/FasterXML/jackson/wiki/Jackson-Releases

したがって、この更新を「既存Java21/Jackson2互換範囲の最新化」と呼ぶことはできても、「あらゆるライブラリを最新メジャーへ移行済み」とは呼ばない。

## 全57項目の監査記録

下表は更新前の実測。上の7座標のみこの変更で更新する。同一系統の最新版を越えるJavaFX/Jackson3は明示保留。

| Maven座標 | 更新前の解決版 | 配布元の最新数字安定版 |
| --- | --- | --- |
| com.fasterxml.jackson.core:jackson-annotations | 2.22 | 2.22 |
| com.fasterxml.jackson.core:jackson-core | 2.22.4 | 2.22.4 |
| com.fasterxml.jackson.core:jackson-databind | 2.22.4 | 2.22.4 |
| jakarta.json:jakarta.json-api | 2.1.3 | 2.1.3 |
| net.bytebuddy:byte-buddy | 1.17.7 | 1.18.16 |
| net.bytebuddy:byte-buddy-agent | 1.17.7 | 1.18.16 |
| org.apache.logging.log4j:log4j-api | 2.26.1 | 2.26.1 |
| org.apache.logging.log4j:log4j-core | 2.26.1 | 2.26.1 |
| org.apache.logging.log4j:log4j-slf4j2-impl | 2.26.1 | 2.26.1 |
| org.apache.maven.plugins:maven-assembly-plugin | 3.8.0 | 3.8.0 |
| org.apache.maven.plugins:maven-clean-plugin | 3.5.0 | 3.5.0 |
| org.apache.maven.plugins:maven-compiler-plugin | 3.16.0 | 3.16.0 |
| org.apache.maven.plugins:maven-deploy-plugin | 3.2.0 | 3.2.0 |
| org.apache.maven.plugins:maven-install-plugin | 3.2.0 | 3.2.0 |
| org.apache.maven.plugins:maven-jar-plugin | 3.5.1 | 3.5.1 |
| org.apache.maven.plugins:maven-resources-plugin | 3.5.0 | 3.5.0 |
| org.apache.maven.plugins:maven-site-plugin | 3.22.0 | 3.22.0 |
| org.apache.maven.plugins:maven-surefire-plugin | 3.6.0 | 3.6.1 |
| org.apiguardian:apiguardian-api | 1.1.2 | 1.1.2 |
| org.controlsfx:controlsfx | 11.2.5 | 11.2.6 |
| org.eclipse.jetty:jetty-alpn-client | 12.1.14 | 12.1.14 |
| org.eclipse.jetty:jetty-client | 12.1.14 | 12.1.14 |
| org.eclipse.jetty:jetty-http | 12.1.14 | 12.1.14 |
| org.eclipse.jetty:jetty-io | 12.1.14 | 12.1.14 |
| org.eclipse.jetty:jetty-security | 12.1.14 | 12.1.14 |
| org.eclipse.jetty:jetty-server | 12.1.14 | 12.1.14 |
| org.eclipse.jetty:jetty-session | 12.1.14 | 12.1.14 |
| org.eclipse.jetty:jetty-util | 12.1.14 | 12.1.14 |
| org.eclipse.jetty.compression:jetty-compression-common | 12.1.14 | 12.1.14 |
| org.eclipse.jetty.compression:jetty-compression-gzip | 12.1.14 | 12.1.14 |
| org.eclipse.jetty.ee8:jetty-ee8-nested | 12.1.14 | 12.1.14 |
| org.eclipse.jetty.ee8:jetty-ee8-proxy | 12.1.14 | 12.1.14 |
| org.eclipse.jetty.ee8:jetty-ee8-security | 12.1.14 | 12.1.14 |
| org.eclipse.jetty.ee8:jetty-ee8-servlet | 12.1.14 | 12.1.14 |
| org.eclipse.jetty.toolchain:jetty-servlet-api | 4.0.9 | 4.0.9 |
| org.eclipse.parsson:parsson | 1.1.9 | 1.1.9 |
| org.jsoup:jsoup | 1.23.2 | 1.23.2 |
| org.jspecify:jspecify | 1.0.0 | 1.0.1 |
| org.junit.jupiter:junit-jupiter-api | 6.1.3 | 6.1.3 |
| org.junit.jupiter:junit-jupiter-engine | 6.1.3 | 6.1.3 |
| org.junit.platform:junit-platform-commons | 6.1.3 | 6.1.3 |
| org.junit.platform:junit-platform-engine | 6.1.3 | 6.1.3 |
| org.junit.platform:junit-platform-launcher | 6.1.3 | 6.1.3 |
| org.mockito:mockito-core | 5.24.0 | 5.24.0 |
| org.objenesis:objenesis | 3.3 | 3.6 |
| org.openjfx:javafx-base | 21.0.13 | 27.0.1 |
| org.openjfx:javafx-controls | 21.0.13 | 27.0.1 |
| org.openjfx:javafx-fxml | 21.0.13 | 27.0.1 |
| org.openjfx:javafx-graphics | 21.0.13 | 27.0.1 |
| org.openjfx:javafx-media | 21.0.13 | 27.0.1 |
| org.openjfx:javafx-swing | 21.0.13 | 27.0.1 |
| org.openjfx:javafx-web | 21.0.13 | 27.0.1 |
| org.opentest4j:opentest4j | 1.3.0 | 1.3.0 |
| org.projectlombok:lombok | 1.18.48 | 1.18.48 |
| org.slf4j:slf4j-api | 2.0.17 | 2.0.20 |
| tools.jackson.core:jackson-core | 未採用 | 3.2.4 |
| tools.jackson.core:jackson-databind | 未採用 | 3.2.4 |

Maven既定プラグインの行は監査runnerでのeffective POM実測であり、他のMaven版でも自動的に同じ版を選ぶという保証ではない。
