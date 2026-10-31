# ビルドと検証

JDK 21とMavenを用意し、リポジトリ直下で実行します。

```sh
mvn verify
python3 scripts/build_feature_distribution.py --jar target/logbook-kai2.jar --out target/kai2-bundle --revision HEAD
python3 scripts/run_feature_distribution.py --help
python3 -m unittest discover -s scripts/tests
bash scripts/verify_bundled_plugins.sh target/kai2-bundle
```

中間集約JARはlogbook-kai2.jar、実機試験用は本体＋8機能JARの管理配布です。

分離JARの検証はscripts/verify_r3.shとCIを参照。Java 25やJavaFX 27の追加検証は対応するCIで行います。テスト用の一時ディレクトリを使い、本番configや履歴を使用しません。

## 品質と試験キット

```sh
mvn formatter:format
mvn formatter:validate
python3 scripts/check_product_conventions.py
node --test scripts/tests/parallel_bridge.test.cjs
mvn dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
python3 scripts/check_runtime_notices.py
python3 scripts/build_parallel_kit.py --bundle target/kai2-bundle --out target/logbook-kai2-linux-test.zip
```

IDEの整形設定とMavenのXMLの一致はcheck_product_conventions.pyで検査します。変更時は両方を更新してください。formatterは613ファイル（今回時点のJava本体・テスト・検証コード）を対象に適用済みです。
