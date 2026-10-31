package logbook.internal.proxy;

import java.nio.ByteBuffer;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Request.ContentListener;

/** Captures each transmitted chunk once; any aggregate overflow leaves a rejected tombstone. */
public final class RequestContentListener implements ContentListener {
    private final HttpServletRequest httpRequest;
    private final CapturedHttpRequestResponse fixedCapture;

    public RequestContentListener(HttpServletRequest request) {
        this(request, null);
    }

    RequestContentListener(HttpServletRequest request, CapturedHttpRequestResponse capture) {
        httpRequest = request;
        fixedCapture = capture;
    }

    @Override
    public void onContent(Request request, ByteBuffer buffer) {
        if (!buffer.hasRemaining())
            return;
        CapturedHttpRequestResponse captured = fixedCapture;
        if (captured == null) {
            synchronized (httpRequest) {
                if (Boolean.TRUE.equals(httpRequest.getAttribute(ReverseProxyServlet.CAPTURE_FINISHED)))
                    return;
                captured = (CapturedHttpRequestResponse) httpRequest
                        .getAttribute(CapturedHttpRequestResponseConst.CONTENT_HOLDER);
                if (captured == null) {
                    captured = new CapturedHttpRequestResponse();
                    httpRequest.setAttribute(CapturedHttpRequestResponseConst.CONTENT_HOLDER, captured);
                }
            }
        }
        captured.putOriginRequest(buffer);
    }
}
