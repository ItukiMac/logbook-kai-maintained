package logbook.internal.proxy;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** CaptureしたOriginのHttpRequest/HttpResponse情報を保持するクラスです。 */
public class CapturedHttpRequestResponse {

    private List<byte[]> originReq = new ArrayList<>();
    private List<byte[]> originRes = new ArrayList<>();

    public void putOriginRequest(byte[] data) {
        // A content callback represents bytes actually sent, not a unique value.
        // Equal adjacent chunks are legitimate (e.g. repeated binary blocks).
        // Deduplicating by value silently truncates the captured request body.
        this.originReq.add(data);
    }

    public InputStream getOriginRequest() {
        return new ByteArrayInputStream2(this.originReq);
    }

    public void putOriginResponse(byte[] data) {
        this.originRes.add(data);
    }

    public InputStream getOriginResponse() {
        return new ByteArrayInputStream2(this.originRes);
    }

    public void clear() {
        this.originReq = null;
        this.originRes = null;
    }
}
