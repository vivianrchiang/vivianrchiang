
package cis5550.webserver;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class Server implements Runnable {
  private static volatile Server INSTANCE = null;
  private static volatile boolean STARTED = false;

  public static void port(int p) {
    ensureInstance();
    INSTANCE.port = p;
  }

  public static class staticFiles {
    public static void location(String path) {
      ensureInstance();
      INSTANCE.staticDir = path;
      startIfNeeded();
    }
  }

  public static void get(String path, Route handler) {
    addRoute("GET", path, handler);
  }

  public static void post(String path, Route handler) {
    addRoute("POST", path, handler);
  }

  public static void put(String path, Route handler) {
    addRoute("PUT", path, handler);
  }

  private static synchronized void addRoute(String method, String path, Route handler) {
    ensureInstance();
    INSTANCE.routes.add(new RouteEntry(method, path, handler));
    startIfNeeded();
  }

  private static synchronized void ensureInstance() {
    if (INSTANCE == null) {
      INSTANCE = new Server();
    }
  }

  private static synchronized void startIfNeeded() {
    if (!STARTED) {
      STARTED = true;
      Thread t = new Thread(INSTANCE, "cis5550-webserver");
      t.setDaemon(true);
      t.start();
      INSTANCE.awaitStarted();
    }
  }

  private void awaitStarted() {
    try {
      startedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException ie) {
    }
  }

  private int port = 80;
  private String staticDir = null;
  private volatile boolean shutdown = false;
  private final List<RouteEntry> routes = new CopyOnWriteArrayList<>();
  private final CountDownLatch startedLatch = new CountDownLatch(1);

  private Server() {}

  @Override
  public void run() {
    try (ServerSocket ss = new ServerSocket()) {
      ss.setReuseAddress(true);
      ss.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
      startedLatch.countDown();
      while (!shutdown) {
        try {
          final Socket s = ss.accept();
          s.setSoTimeout(30_000);
          new Thread(() -> handleConnection(s)).start();
        } catch (IOException ioe) {
        }
      }
    } catch (IOException e) {
      startedLatch.countDown();
    }
  }

  private void handleConnection(Socket s) {
    try (Socket sock = s;
         InputStream in = sock.getInputStream();
         OutputStream out = sock.getOutputStream()) {

      String requestLine = readLine(in);
      if (requestLine == null || requestLine.isEmpty()) {
        return;
      }
      String[] parts = requestLine.split(" ", 3);
      if (parts.length < 3) {
        sendSimple(out, 400, "Bad Request", null, null);
        return;
      }
      String method = parts[0];
      String urlWithQuery = parts[1];
      String protocol = parts[2];

      Map<String,String> headers = new LinkedHashMap<>();
      String line;
      while ((line = readLine(in)) != null && !line.isEmpty()) {
        int idx = line.indexOf(':');
        if (idx > 0) {
          String k = line.substring(0, idx).trim();
          String v = line.substring(idx+1).trim();
          headers.put(k, v);
        }
      }

      int contentLength = 0;
      if (headers.containsKey("Content-Length")) {
        try { contentLength = Integer.parseInt(headers.get("Content-Length").trim()); } catch (Exception ignore) {}
      }

      byte[] bodyRaw = new byte[0];
      if (contentLength > 0) {
        bodyRaw = readN(in, contentLength);
      }

      String urlPath = urlWithQuery;
      String query = null;
      int qIdx = urlWithQuery.indexOf('?');
      if (qIdx >= 0) {
        urlPath = urlWithQuery.substring(0, qIdx);
        query = urlWithQuery.substring(qIdx+1);
      }

      RouteMatch match = findMatch(method, urlPath);
      if (match == null) {
        if (staticDir != null) {
          Path p = resolveStatic(staticDir, urlPath);
          if (p != null && Files.exists(p) && Files.isRegularFile(p)) {
            byte[] bytes = Files.readAllBytes(p);
            Map<String,String> hdrs = new LinkedHashMap<>();
            hdrs.put("Content-Type", guessContentType(p));
            hdrs.put("Content-Length", Integer.toString(bytes.length));
            hdrs.put("Connection", "close");
            writeResponseLine(out, 200, "OK");
            writeHeaders(out, hdrs);
            out.write(bytes);
            return;
          }
        }
        sendSimple(out, 404, "Not Found", null, null);
        return;
      }

      Map<String,String> qp = parseQueryParams(query);
      if (contentLength > 0 && isFormUrlEncoded(headers.getOrDefault("Content-Type",""))) {
        String form = new String(bodyRaw, StandardCharsets.ISO_8859_1);
        Map<String,String> formParams = parseQueryParams(form);
        qp.putAll(formParams);
      }

      ResponseImpl resp = new ResponseImpl(out, protocol);
      Request req = new RequestImplCompat(
          method, urlPath, protocol,
          (InetSocketAddress) sock.getRemoteSocketAddress(),
          headers, qp, match.params,
          bodyRaw, this);

      Object routeResult = null;
      boolean handlerThrew = false;
      try {
        routeResult = match.route.handle(req, resp);
      } catch (Exception ex) {
        handlerThrew = true;
      }

      if (resp.headersFlushed) {
        return;
      }

      if (handlerThrew) {
        sendSimple(out, 500, "Internal Server Error", null, null);
        return;
      }

      byte[] bodyToSend = null;
      if (routeResult != null) {
        bodyToSend = routeResult.toString().getBytes(StandardCharsets.ISO_8859_1);
      } else if (resp.bodyBytes != null) {
        bodyToSend = resp.bodyBytes;
      } else if (resp.bodyString != null) {
        bodyToSend = resp.bodyString.getBytes(StandardCharsets.ISO_8859_1);
      }

      Map<String,String> hdrs = new LinkedHashMap<>(resp.headers);
      hdrs.put("Connection", "close");
      if (bodyToSend != null) {
        hdrs.put("Content-Length", Integer.toString(bodyToSend.length));
      }

      writeResponseLine(out, resp.statusCode, resp.reasonPhrase);
      writeHeaders(out, hdrs);
      if (bodyToSend != null) {
        out.write(bodyToSend);
      }
    } catch (IOException ioe) {
    } catch (Exception e) {
    }
  }

  private RouteMatch findMatch(String method, String urlPath) {
    for (RouteEntry re : routes) {
      if (!re.method.equalsIgnoreCase(method)) continue;
      Map<String,String> params = new LinkedHashMap<>();
      if (matchPath(re.path, urlPath, params)) {
        return new RouteMatch(re.route, params);
      }
    }
    return null;
  }

  private static boolean matchPath(String pattern, String url, Map<String,String> outParams) {
    if (pattern == null) return false;
    if (!pattern.startsWith("/")) pattern = "/" + pattern;
    if (!url.startsWith("/")) url = "/" + url;
    String[] p = pattern.split("/");
    String[] u = url.split("/");
    if (p.length != u.length) return false;
    for (int i = 0; i < p.length; i++) {
      String pp = p[i];
      String uu = u[i];
      if (pp.isEmpty() && uu.isEmpty()) continue;
      if (pp.startsWith(":")) {
        String name = pp.substring(1);
        outParams.put(name, urlDecode(uu));
      } else {
        if (!pp.equals(uu)) return false;
      }
    }
    return true;
  }

  private static Map<String,String> parseQueryParams(String q) {
    Map<String,String> m = new LinkedHashMap<>();
    if (q == null || q.isEmpty()) return m;
    String[] parts = q.split("&");
    for (String kv : parts) {
      if (kv.isEmpty()) continue;
      String name, val;
      int eq = kv.indexOf('=');
      if (eq >= 0) {
        name = kv.substring(0, eq);
        val  = kv.substring(eq+1);
      } else {
        name = kv;
        val = "";
      }
      name = urlDecode(name);
      val  = urlDecode(val);
      if (m.containsKey(name)) {
        m.put(name, m.get(name)+","+val);
      } else {
        m.put(name, val);
      }
    }
    return m;
  }

  private static boolean isFormUrlEncoded(String ct) {
    if (ct == null) return false;
    return ct.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
  }

  private static String urlDecode(String s) {
    try {
      return java.net.URLDecoder.decode(s, "UTF-8");
    } catch (Exception e) {
      return s;
    }
  }

  private static Path resolveStatic(String baseDir, String urlPath) {
    try {
      if (urlPath.equals("/")) urlPath = "/index.html";
      Path root = Paths.get(baseDir).toAbsolutePath().normalize();
      Path p = root.resolve(urlPath.substring(1)).normalize();
      if (!p.startsWith(root)) return null;
      return p;
    } catch (Exception e) {
      return null;
    }
  }

  private static String guessContentType(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
    if (name.endsWith(".txt")) return "text/plain";
    if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
    if (name.endsWith(".png")) return "image/png";
    if (name.endsWith(".gif")) return "image/gif";
    if (name.endsWith(".css")) return "text/css";
    if (name.endsWith(".js")) return "application/javascript";
    return "application/octet-stream";
  }

  private static void sendSimple(OutputStream out, int code, String reason, Map<String,String> headers, byte[] body) throws IOException {
    writeResponseLine(out, code, reason);
    Map<String,String> hdrs = new LinkedHashMap<>();
    if (headers != null) hdrs.putAll(headers);
    hdrs.put("Connection", "close");
    if (body != null) {
      hdrs.put("Content-Length", Integer.toString(body.length));
    }
    writeHeaders(out, hdrs);
    if (body != null) out.write(body);
  }

  private static void writeResponseLine(OutputStream out, int code, String reason) throws IOException {
    String rl = "HTTP/1.1 " + code + " " + reason + "\r\n";
    out.write(rl.getBytes(StandardCharsets.ISO_8859_1));
  }

  private static void writeHeaders(OutputStream out, Map<String,String> headers) throws IOException {
    for (Map.Entry<String,String> e : headers.entrySet()) {
      String line = e.getKey()+": "+e.getValue()+"\r\n";
      out.write(line.getBytes(StandardCharsets.ISO_8859_1));
    }
    out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
  }

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    int prev = -1, curr;
    while ((curr = in.read()) != -1) {
      if (prev == '\r' && curr == '\n') break;
      if (prev != -1) baos.write(prev);
      prev = curr;
    }
    if (prev != -1 && !(prev == '\r')) baos.write(prev);
    byte[] line = baos.toByteArray();
    if (line.length == 0 && curr == -1) return null;
    return new String(line, StandardCharsets.ISO_8859_1);
  }

  private static byte[] readN(InputStream in, int n) throws IOException {
    byte[] buf = new byte[n];
    int off = 0;
    while (off < n) {
      int r = in.read(buf, off, n - off);
      if (r <= 0) break;
      off += r;
    }
    if (off == n) return buf;
    return Arrays.copyOf(buf, off);
  }

  private static final class RouteEntry {
    final String method;
    final String path;
    final Route route;
    RouteEntry(String m, String p, Route r) { this.method=m; this.path=p; this.route=r; }
  }
  private static final class RouteMatch {
    final Route route;
    final Map<String,String> params;
    RouteMatch(Route r, Map<String,String> p) { this.route=r; this.params=p; }
  }

  static final class ResponseImpl implements Response {
    final OutputStream out;
    final String protocol;
    int statusCode = 200;
    String reasonPhrase = "OK";
    final Map<String,String> headers = new LinkedHashMap<>();
    String bodyString = null;
    byte[] bodyBytes = null;
    boolean headersFlushed = false;

    ResponseImpl(OutputStream out, String protocol) {
      this.out = out;
      this.protocol = protocol;
    }

    @Override public void body(String body) { this.bodyString = body; this.bodyBytes = null; }
    @Override public void bodyAsBytes(byte[] body) { this.bodyBytes = body; this.bodyString = null; }

    @Override public void type(String contentType) { header("Content-Type", contentType); }
    @Override public void header(String name, String value) { headers.put(name, value); }

    @Override public void status(int statusCode, String reasonPhrase) {
      this.statusCode = statusCode;
      this.reasonPhrase = reasonPhrase;
    }

    @Override public void write(byte[] b) throws Exception {
      if (!headersFlushed) {
        Map<String,String> hdrs = new LinkedHashMap<>(headers);
        hdrs.put("Connection", "close");
        writeResponseLine(out, statusCode, reasonPhrase);
        writeHeaders(out, hdrs);
        headersFlushed = true;
      }
      if (b != null && b.length > 0) {
        out.write(b);
      }
    }

    @Override public void redirect(String url, int responseCode) { }
    @Override public void halt(int statusCode, String reasonPhrase) { }
  }

  static final class RequestImplCompat implements Request {
    final String method;
    final String url;
    final String protocol;
    final InetSocketAddress remoteAddr;
    final Map<String,String> headersRaw;
    final Map<String,String> headersLower;
    final Map<String,String> queryParams;
    final Map<String,String> params;
    final byte[] bodyRaw;
    final Server server;

    RequestImplCompat(String method, String url, String protocol, InetSocketAddress remoteAddr,
                      Map<String,String> headers, Map<String,String> queryParams, Map<String,String> params,
                      byte[] bodyRaw, Server server) {
      this.method = method;
      this.url = url;
      this.protocol = protocol;
      this.remoteAddr = remoteAddr;
      this.headersRaw = new LinkedHashMap<>();
      this.headersLower = new LinkedHashMap<>();
      if (headers != null) {
        for (Map.Entry<String,String> e : headers.entrySet()) {
          this.headersRaw.put(e.getKey(), e.getValue());
          this.headersLower.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
      }
      this.queryParams = queryParams != null ? queryParams : new LinkedHashMap<>();
      this.params = params != null ? params : new LinkedHashMap<>();
      this.bodyRaw = bodyRaw != null ? bodyRaw : new byte[0];
      this.server = server;
    }

    public String ip() { return remoteAddr.getAddress().getHostAddress(); }
    public int port() { return remoteAddr.getPort(); }

    public String requestMethod() { return method; }
    public String url() { return url; }
    public String protocol() { return protocol; }

    public int contentLength() { return bodyRaw.length; }
    public byte[] bodyAsBytes() { return Arrays.copyOf(bodyRaw, bodyRaw.length); }
    public String body() { return new String(bodyRaw, StandardCharsets.ISO_8859_1); }

    public Set<String> headers() { return headersLower.keySet(); }
    public String headers(String name) {
      if (name == null) return null;
      return headersLower.get(name.toLowerCase(Locale.ROOT));
    }
    public String contentType() { return headersLower.get("content-type"); }

    public String queryParams(String param) { return queryParams.get(param); }
    public Set<String> queryParams() { return queryParams.keySet(); }

    public String params(String name) { return params.get(name); }
    public Map<String,String> params() { return params; }
  }
}
