package eu.glazelab.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/**
 * Refuses a request body other than multipart above {@link #MAX_BYTES} with 413 {@link #TOO_LARGE} before anything
 * parses it: by {@code Content-Length} at once, and for a body without one (chunked) as soon as the bytes read pass the
 * cap. Multipart uploads keep Spring's 12 MB multipart limit. Registered only in Spring Security's chain, after the rate
 * limit, so a refused body still counts.
 */
public class BodySizeFilter extends OncePerRequestFilter {
    /** 128 KB: several times the largest body the app sends (a combination with 30 steps and long texts). */
    public static final int MAX_BYTES = 128 * 1024;
    public static final String TOO_LARGE = "This request is too large.";

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String type = request.getContentType();
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > MAX_BYTES) {
            SecurityConfig.error(response, HttpStatus.CONTENT_TOO_LARGE.value(), TOO_LARGE);
            return;
        }
        chain.doFilter(new Capped(request), response);
    }

    /**
     * Thrown while a body without a declared length is read past the cap; {@code ApiErrorHandler} answers 413, also when
     * a message converter wrapped it.
     */
    public static final class TooLarge extends ResponseStatusException {
        TooLarge() { super(HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE); }
    }

    private static final class Capped extends HttpServletRequestWrapper {
        private ServletInputStream stream;

        Capped(HttpServletRequest request) { super(request); }

        @Override public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new Counting(super.getInputStream());
            return stream;
        }

        @Override public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class Counting extends ServletInputStream {
        private final ServletInputStream body;
        private long read;

        Counting(ServletInputStream body) { this.body = body; }

        private int count(int bytes) {
            if (bytes > 0 && (read += bytes) > MAX_BYTES) throw new TooLarge();
            return bytes;
        }

        @Override public int read() throws IOException {
            int value = body.read();
            if (value >= 0) count(1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            return count(body.read(buffer, offset, length));
        }
        @Override public boolean isFinished() { return body.isFinished(); }
        @Override public boolean isReady() { return body.isReady(); }
        @Override public void setReadListener(ReadListener listener) { body.setReadListener(listener); }
    }
}
