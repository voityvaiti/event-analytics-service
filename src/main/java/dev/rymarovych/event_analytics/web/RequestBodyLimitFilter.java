package dev.rymarovych.event_analytics.web;

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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps ingest bodies at 512 KiB by counting the bytes actually read, so a body sent without
 * Content-Length meets the same limit; the overflow reaches the client as a 413.
 */
@Component
class RequestBodyLimitFilter extends OncePerRequestFilter {

  static final long MAX_BODY_BYTES = 512 * 1024;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/v1/events");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(new LimitedBodyRequest(request), response);
  }

  private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

    LimitedBodyRequest(HttpServletRequest request) {
      super(request);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (getContentLengthLong() > MAX_BODY_BYTES) {
        throw new RequestBodyTooLargeException(MAX_BODY_BYTES);
      }
      return new LimitedInputStream(super.getInputStream());
    }

    @Override
    public BufferedReader getReader() throws IOException {
      String encoding = getCharacterEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }

  private static final class LimitedInputStream extends ServletInputStream {

    private final ServletInputStream body;
    private long bytesRead;

    LimitedInputStream(ServletInputStream body) {
      this.body = body;
    }

    @Override
    public int read() throws IOException {
      int next = body.read();
      if (next >= 0) {
        counted(1);
      }
      return next;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int count = body.read(buffer, offset, length);
      if (count > 0) {
        counted(count);
      }
      return count;
    }

    private void counted(int count) throws RequestBodyTooLargeException {
      bytesRead += count;
      if (bytesRead > MAX_BODY_BYTES) {
        throw new RequestBodyTooLargeException(MAX_BODY_BYTES);
      }
    }

    @Override
    public boolean isFinished() {
      return body.isFinished();
    }

    @Override
    public boolean isReady() {
      return body.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      body.setReadListener(listener);
    }
  }
}
