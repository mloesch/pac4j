package org.pac4j.core.resource;

import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.util.CommonHelper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;

/**
 * An helper with Spring Resource.
 *
 * @author Jerome LELEU
 * @since 6.0.0
 */
public final class SpringResourceHelper {

    public static final String RESOURCE_PREFIX = "resource:";

    public static final String CLASSPATH_PREFIX = "classpath:";

    public static final String FILE_PREFIX = "file:";

    public static InputStream getResourceInputStream(final Resource resource, final Proxy proxy, final SSLSocketFactory sslSocketFactory,
                                                     final HostnameVerifier hostnameVerifier, final int connectTimeout,
                                                     final int readTimeout) throws IOException {
        if (resource instanceof UrlResource) {
            URLConnection con;
            if (proxy != null) {
                con = resource.getURL().openConnection(proxy);
            } else {
                con = resource.getURL().openConnection();
            }
            if (con instanceof HttpsURLConnection) {
                HttpsURLConnection connection = (HttpsURLConnection) con;
                if (sslSocketFactory != null) {
                    connection.setSSLSocketFactory(sslSocketFactory);
                }
                if (hostnameVerifier != null) {
                    connection.setHostnameVerifier(hostnameVerifier);
                }
            }

            try {
                con.setConnectTimeout(connectTimeout);
                con.setReadTimeout(readTimeout);
                return con.getInputStream();
            } catch (final Exception e) {
                if (con instanceof HttpURLConnection) {
                    ((HttpURLConnection) con).disconnect();
                }
                throw new TechnicalException("Error getting URL resource", e);
            }
        }

        return resource.getInputStream();
    }

    public static Resource buildResourceFromPath(final String path) {
        CommonHelper.assertNotBlank("path", path);
        try {
            if (path.startsWith(RESOURCE_PREFIX)) {
                return new ClassPathResource(path.substring(RESOURCE_PREFIX.length()));
            }
            if (path.startsWith(CLASSPATH_PREFIX)) {
                return new ClassPathResource(path.substring(CLASSPATH_PREFIX.length()));
            }
            if (path.startsWith(HttpConstants.SCHEME_HTTP) || path.startsWith(HttpConstants.SCHEME_HTTPS)) {
                return new UrlResource(new URL(path));
            }
            if (path.startsWith(FILE_PREFIX)) {
                return new FileSystemResource(path.substring(FILE_PREFIX.length()));
            }
            return new FileSystemResource(path);
        } catch (final Exception e) {
            throw new TechnicalException(e);
        }
    }


    public static UrlResource newUrlResource(final String url) {
        try {
            return new UrlResource(url);
        } catch (final MalformedURLException e) {
            throw new TechnicalException(e);
        }
    }
}
