package logbook.internal.proxy;

/**
 * Legacy application-visible servlet type.
 *
 * The supported Jetty 12 EE8 proxy implementation retains javax.servlet
 * compatibility. Keep this adapter so existing application code and plugins
 * do not depend on Jetty's relocated servlet implementation type.
 */
public class ProxyServlet extends org.eclipse.jetty.ee8.proxy.ProxyServlet {
    private static final long serialVersionUID = 1L;
}
