package logbook.internal.proxy;

import java.util.List;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.util.Callback;
import logbook.bean.AppConfig;
import logbook.core.LogBookCoreServices;
import logbook.internal.logger.LoggerHolder;
import logbook.internal.net.RequestMetaDataWrapper;
import logbook.internal.net.ResponseMetaDataWrapper;
import logbook.listener.ContentListenerSpi;

/** Jetty 12 EE8 forwarding and capture; the existing listener SPI is unchanged. */
public final class ReverseProxyServlet extends ProxyServlet {
    private static final long serialVersionUID = 1L;
    static final String CAPTURE_FINISHED = "logbook.capture-finished";
    private transient volatile List<ContentListenerSpi> listeners;
    private final transient CaptureDispatchQueue notifications;
    private final transient CaptureBudget budget;
    private final long exchangeLimit;

    public ReverseProxyServlet() {
        this(new CaptureDispatchQueue(), CaptureBudget.SHARED, CapturedHttpRequestResponse.MAX_EXCHANGE_BYTES);
    }

    ReverseProxyServlet(CaptureDispatchQueue queue, CaptureBudget budget, long exchangeLimit) {
        notifications = queue;
        this.budget = budget;
        this.exchangeLimit = exchangeLimit;
    }

    @Override
    public void destroy() {
        notifications.close();
        super.destroy();
    }

    @Override
    protected Request newProxyRequest(HttpServletRequest clientRequest, String rewrittenTarget) {
        Request proxyRequest = super.newProxyRequest(clientRequest, rewrittenTarget);
        proxyRequest.onRequestContent(new RequestContentListener(clientRequest, capture(clientRequest)));
        return proxyRequest;
    }

    @Override
    protected HttpClient newHttpClient() {
        HttpClient client = super.newHttpClient();
        if (AppConfig.get().isUseProxy()) {
            client.getProxyConfiguration().addProxy(
                    new HttpProxy(AppConfig.get().getProxyHost(), AppConfig.get().getProxyPort()));
        }
        return client;
    }

    private CapturedHttpRequestResponse capture(HttpServletRequest request) {
        synchronized (request) {
            if (Boolean.TRUE.equals(request.getAttribute(CAPTURE_FINISHED)))
                return null;
            var captured = (CapturedHttpRequestResponse) request
                    .getAttribute(CapturedHttpRequestResponseConst.CONTENT_HOLDER);
            if (captured == null) {
                captured = new CapturedHttpRequestResponse(budget, exchangeLimit,
                        CapturedHttpRequestResponse.MAX_EXCHANGE_CHUNKS);
                request.setAttribute(CapturedHttpRequestResponseConst.CONTENT_HOLDER, captured);
            }
            return captured;
        }
    }

    private static CapturedHttpRequestResponse finish(HttpServletRequest request) {
        synchronized (request) {
            var captured = (CapturedHttpRequestResponse) request
                    .getAttribute(CapturedHttpRequestResponseConst.CONTENT_HOLDER);
            request.setAttribute(CAPTURE_FINISHED, true);
            request.removeAttribute(CapturedHttpRequestResponseConst.CONTENT_HOLDER);
            return captured;
        }
    }

    @Override
    protected void onResponseContent(HttpServletRequest clientRequest,
            HttpServletResponse proxyResponse, Response serverResponse, byte[] bytes,
            int offset, int length, Callback callback) {
        var captured = capture(clientRequest);
        if (captured != null && length > 0)
            captured.putOriginResponse(bytes, offset, length);
        super.onResponseContent(clientRequest, proxyResponse, serverResponse, bytes, offset, length, callback);
    }

    @Override
    protected void onProxyResponseSuccess(HttpServletRequest clientRequest,
            HttpServletResponse proxyResponse, Response serverResponse) {
        var captured = finish(clientRequest);
        boolean handedOff = false;
        try {
            if (captured != null && proxyResponse.getStatus() == HttpServletResponse.SC_OK && captured.seal()) {
                RequestMetaDataWrapper req = new RequestMetaDataWrapper();
                req.set(clientRequest);
                ResponseMetaDataWrapper res = new ResponseMetaDataWrapper();
                res.set(proxyResponse);
                handedOff = notifications.submit(() -> invoke(req, res, captured), captured);
                if (!handedOff)
                    LoggerHolder.get().warn("通信捕捉の通知待ち上限に達したため通知を省略しました（通信は継続）");
            } else if (captured != null && captured.rejectionReason() != null) {
                LoggerHolder.get().warn("通信捕捉を上限超過で破棄しました（部分データは通知しません）: " + captured.rejectionReason());
            }
        } catch (Exception ex) {
            LoggerHolder.get().warn("リバースプロキシ サーブレットで例外が発生", ex);
        } finally {
            if (captured != null && !handedOff)
                captured.clear();
        }
        super.onProxyResponseSuccess(clientRequest, proxyResponse, serverResponse);
    }

    @Override
    protected void onProxyResponseFailure(HttpServletRequest clientRequest,
            HttpServletResponse proxyResponse, Response serverResponse, Throwable failure) {
        var captured = finish(clientRequest);
        if (captured != null)
            captured.clear();
        super.onProxyResponseFailure(clientRequest, proxyResponse, serverResponse, failure);
    }

    private void invoke(RequestMetaDataWrapper req, ResponseMetaDataWrapper res,
            CapturedHttpRequestResponse captured) {
        try {
            try (var body = captured.getOriginResponse()) {
                ResponseMetaDataWrapper.validateBody(body);
            }
            List<ContentListenerSpi> current = listeners;
            if (current == null) {
                synchronized (this) {
                    current = listeners;
                    if (current == null) {
                        current = LogBookCoreServices.getServiceProviders(ContentListenerSpi.class)
                                .collect(Collectors.toList());
                        listeners = current;
                    }
                }
            }
            for (ContentListenerSpi listener : current) {
                // Borrowed streams are private to this callback and closed even when a plugin fails.
                try (var requestBody = captured.getOriginRequest(); var responseBody = captured.getOriginResponse()) {
                    RequestMetaDataWrapper eachReq = req.clone();
                    eachReq.set(requestBody);
                    if (listener.test(eachReq)) {
                        ResponseMetaDataWrapper eachRes = res.clone();
                        eachRes.set(responseBody);
                        try (var decoded = eachRes.getResponseBody().orElseThrow()) {
                            listener.accept(eachReq, eachRes);
                        }
                    }
                } catch (Exception ex) {
                    LoggerHolder.get().warn("リバースプロキシ listener の処理で例外が発生", ex);
                }
            }
        } catch (Exception ex) {
            LoggerHolder.get().warn("リバースプロキシの通知処理で例外が発生", ex);
        } finally {
            captured.clear();
        }
    }
}
