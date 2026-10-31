# ビルドと検証

JDK 21とMavenを用意し、リポジトリ直下で実行します。

```sh
mvn verify
python3 scripts/build_feature_distribution.py --jar target/logbook-kai.jar --out target/kai2-bundle --revision HEAD
python3 scripts/run_feature_distribution.py --help
python3 -m unittest discover -s scripts/tests
bash scripts/verify_bundled_plugins.sh target/kai2-bundle
```

現在、Mavenの中間集約JAR名はlogbook-kai.jarです。分離配布物の名称変更と中間成果物名の変更を混同しないでください。中間名・起動/更新経路の統一は計画で追跡します。

分離JARの検証はscripts/verify_r3.shとCIを参照。Java 25やJavaFX 27の追加検証は対応するCIで行います。テスト用の一時ディレクトリを使い、本番configや履歴を使用しません。
