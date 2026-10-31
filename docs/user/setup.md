# 導入と接続

Linuxの並行実機試験は[専用手順](linux-parallel-test.md)を使用します。旧logbook・KLBを残し、新しいフォルダー・ポート・更新先へ分離します。

基準ランタイムはJava 21です。Java 25/JavaFX 27は追加互換性検証で、基準配布物と区別します。Windows/macOSの実機検証は今回のLinux試験とは別扱いです。

本体とfeaturesのJARは配布一式の同じ世代を使います。現在情報は再接続で取得し、旧設定・キャッシュは移行しません。[残すデータの取り込み](data-import.md)を参照してください。
