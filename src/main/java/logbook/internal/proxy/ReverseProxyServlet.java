package logbook.internal.proxy;

import java.util.Arrays;
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
import logbook.internal.ThreadManager;
import logbook.internal.logger.LoggerHolder;
import logbook.internal.net.RequestMetaDataWrapper;
import logbook.internal.net.ResponseMetaDataWrapper;
import logbook.listener.ContentListenerSpi;

/** Jetty 12 EE8 forwarding and capture; the existing listener SPI is unchanged. */
public final class ReverseProxyServlet extends ProxyServlet {
    private static final long serialVersionUID = 1L;
    private transient volatile List<ContentListenerSpi> listeners;

    @Override protected Request newProxyRequest(HttpServletRequest clientRequest, String rewrittenTarget) {
        Request proxyRequest = super.newProxyRequest(clientRequest, rewrittenTarget);
        proxyRequest.onRequestContent(new RequestContentListener(clientRequest));
        return proxyRequest;
    }
    @Override protected HttpClient newHttpClient() {
        HttpClient client = super.newHttpClient();
        if (AppConfig.get().isUseProxy()) {
            client.getProxyConfiguration().addProxy(
                    new HttpProxy(AppConfig.get().getProxyHost(), AppConfig.get().getProxyPort()));
        }
        return client;
    }
    private static CapturedHttpRequestResponse capture(HttpServletRequest request) {
        CapturedHttpRequestResponse captured = (CapturedHttpRequestResponse)
                request.getAttribute(CapturedHttpRequestResponseConst.CONTENT_HOLDER);
        if (captured == null) {
            captured = new CapturedHttpRequestResponse();
            request.setAttribute(CapturedHttpRequestResponseConst.CONTENT_HOLDER, captured);
        }
        return captured;
    }
    @Override protected void onResponseContent(HttpServletRequest clientRequest,
            HttpServletResponse proxyResponse, Response serverResponse, byte[] bytes,
            int offset, int length, Callback callback) {
        if (length > 0) capture(clientRequest).putOriginResponse(Arrays.copyOfRange(bytes, offset, offset + length));
        super.onResponseContent(clientRequest, proxyResponse, serverResponse, bytes, offset, length, callback);
    }
    @Override protected void onProxyResponseSuccess(HttpServletRequest clientRequest,
            HttpServletResponse proxyResponse, Response serverResponse) {
        try {
            if (proxyResponse.getStatus() == HttpServletResponse.SC_OK) {
                CapturedHttpRequestResponse captured = (CapturedHttpRequestResponse)
                        clientRequest.getAttribute(CapturedHttpRequestResponseConst.CONTENT_HOLDER);
                if (captured != null) {
                    RequestMetaDataWrapper req = new RequestMetaDataWrapper();
                    req.set(clientRequest);
                    ResponseMetaDataWrapper res = new ResponseMetaDataWrapper();
                    res.set(proxyResponse);
                    ThreadManager.getExecutorService().submit(() -> invoke(req, res, captured));
                }
            }
        } catch (Exception ex) {
            LoggerHolder.get().warn("リバースプロキシ サーブレットで例外が発生", ex);
        } finally {
            clientRequest.removeAttribute(CapturedHttpRequestResponseConst.CONTENT_HOLDER);
        }
        super.onProxyResponseSuccess(clientRequest, proxyResponse, serverResponse);
    }
    @Override protected void onProxyResponseFailure(HttpServletRequest clientRequest,
            HttpServletResponse proxyResponse, Response serverResponse, Throwable failure) {
        clientRequest.removeAttribute(CapturedHttpRequestResponseConst.CONTENT_HOLDER);
        super.onProxyResponseFailure(clientRequest, proxyResponse, serverResponse, failure);
    }
    private void invoke(RequestMetaDataWrapper req, ResponseMetaDataWrapper res,
            CapturedHttpRequestResponse captured) {
        try {
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
                // A broken plugin predicate must not suppress all later plugins.
                try {
                    RequestMetaDataWrapper eachReq = req.clone();
                    eachReq.set(captured.getOriginRequest());
                    if (listener.test(eachReq)) {
                        ResponseMetaDataWrapper eachRes = res.clone();
                        eachRes.set(captured.getOriginResponse());
                        ThreadManager.getExecutorService().submit(() -> {
                            try {
                                listener.accept(eachReq, eachRes);
                            } catch (Exception ex) {
                                LoggerHolder.get().warn("リバースプロキシ listener で例外が発生", ex);
                            }
                        });
                    }
                } catch (Exception ex) {
                    LoggerHolder.get().warn("リバースプロキシ listener の判定/登録で例外が発生", ex);
                }
            }
        } catch (Exception ex) {
            LoggerHolder.get().warn("リバースプロキシの通知処理で例外が発生", ex);
        } finally {
            captured.clear();
        }
    }
}
