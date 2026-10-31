package logbook.listener;

import logbook.net.RequestMetaData;
import logbook.net.ResponseMetaData;

/**
 * ResponseBodyを処理するサービス・プロバイダ・インタフェース(SPI)です<br>
 * <br>
 * まずRequest URIに対して{@link #test(RequestMetaData)}が呼び出されます。{@link #test(RequestMetaData)}が処理対象URIか判定し、trueを返してかつ、リクエストに対する
 * レスポンスが正常に返ってきた場合に{@link #accept(RequestMetaData, ResponseMetaData)}が呼び出され、ResponseBodyの処理を実施します。<br>
 * <br>
 * リクエストは並列処理される可能性があるため同期化が必要になることがあります。
 */
public interface ContentListenerSpi {

    /**
     * 処理対象URIか判定を行います
     *
     * @param requestMetaData リクエストに含まれている情報
     * @return 処理対象URIの場合true
     */
    boolean test(RequestMetaData requestMetaData);

    /**
     * レスポンスを処理します
     * <p>ボディストリームはこのコールバック内で読み取ってください。戻った後はホストが閉じます。
     * 非同期処理へ渡す場合は、必要な解析結果をコールバック内で作成してください。</p>
     *
     * @param requestMetaData リクエストに含まれている情報
     * @param responseMetaData レスポンスに含まれている情報
     */
    void accept(RequestMetaData requestMetaData, ResponseMetaData responseMetaData);
}
