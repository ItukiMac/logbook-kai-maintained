# 第三者情報

logbook-kai2のコードはMITライセンスで提供します。継承コードの著作権表示と許諾文は[LICENSE](LICENSE)に保持しています。第三者ソフトウェアはそれぞれの条件で提供されます。

- 接続機能: modules/bridge/LICENSE と ORIGIN.md。
- 共有機能: modules/sharing/LICENSE と ORIGIN.md。
- 任務定義: modules/quests/LICENSE と CATALOG-ORIGIN.md。カタログ内の出典・ライセンスを保持します。
- ブラウザ拡張: extensions/browser-bridge/LICENSE と NOTICE.md。
- ランタイム依存関係: [機械可読一覧](licenses/runtime-inventory.json)。各依存JARのLICENSE/NOTICEをlicenses/runtimeへ保持しています。

JARに許諾文が含まれないOpenJFX 21.0.13、Jetty 12.1.14、ControlsFX 11.2.6は、その版の上流ソースから取得しました。固定コミットは一覧に記録しています。OpenJFXの第三者ネイティブ部品のlegal文書も含めています。

対応ソースの参照先:
- https://github.com/openjdk/jfx21u/tree/ba6a406466297926dc39fa7df7b3ac3402fdda12
- https://github.com/jetty/jetty.project/tree/15cecbd757a2f6b0fdc6a8944f4c6d0020b17da0
- https://github.com/controlsfx/controlsfx/tree/4c003e9850a5044720bf14f192209b00d5554b43

この一覧は配布基準のJavaFX 21系に対応します。JavaFX 27は追加互換試験であり、このまま公開用ランタイムへ差し替えません。依存版を更新した際は通知文も再生成・照合します。
