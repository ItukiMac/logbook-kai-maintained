# コーディング規約

## 基準

既存の `.settings/org.eclipse.jdt.core.prefs` をJava整形の正本とします。スペース4、行幅120の設定を保持します。整形方式を途中でGoogle方式へ重ねて適用しません。

既存設定に規定のない命名・import・Javadoc・例外処理は[Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)を採用します。Java整形部分は上記既存設定を優先するため、完全なGoogle Style準拠とは称しません。

- 型はUpperCamelCase、変数・メソッドはlowerCamelCase、定数はUPPER_SNAKE_CASE。
- パッケージは小文字。ワイルドカードimportを避け、未使用importを削除。
- 意味のない略語を避け、単位はdurationMillisなど名前で明示。
- 例外を握りつぶさず、回復方法または利用者に必要な情報を示す。
- 公開APIの契約、スレッド条件、失敗条件をJavadocに記載。
- 外部APIのフィールド名は境界で対応付け、内部の用語と混同しない。

## 適用

新規・変更コードから適用し、残りはモジュール単位で整形専用変更として揃えます。自動生成物・第三者配布物は対象外。formatterとCIへの組み込みは品質改善計画で追跡し、文書化のみを適用完了としません。

Python・シェルは既存の実行条件を維持し、規約不足部分はGoogleの該当言語規約を参照します。FXML/XML/JSONはUTF-8、既存の字下げに従い、生成データの形式は変更しません。
