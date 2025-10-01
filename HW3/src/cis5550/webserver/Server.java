
package cis5550.webserver;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Server {
  private static final Map<String,List<RouteEntry>> routes = new ConcurrentHashMap<>();
  private static final Map<String,SessionImpl> sessions = new ConcurrentHashMap<>();
  private static final ExecutorService pool = Executors.newCachedThreadPool();
  private static final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor();
  private static volatile boolean started = false;
  private static volatile int httpPort = 80;
  private static volatile Integer httpsPort = null;
  private static volatile File staticBase = null;
  static {
    sweeper.scheduleAtFixedRate(() -> {
      long now = System.currentTimeMillis();
      for (Iterator<Map.Entry<String,SessionImpl>> it = sessions.entrySet().iterator(); it.hasNext();) {
        Map.Entry<String,SessionImpl> e = it.next();
        SessionImpl s = e.getValue();
        if (s.isExpired(now)) it.remove();
      }
    }, 5, 5, TimeUnit.SECONDS);
  }

  public static void port(int p) { httpPort = p; }
  public static void securePort(int p) { httpsPort = p; }

  public static void get(String path, Route route) { addRoute("GET", path, route); }
  public static void post(String path, Route route) { addRoute("POST", path, route); }
  public static void put(String path, Route route) { addRoute("PUT", path, route); }
  public static void delete(String path, Route route) { addRoute("DELETE", path, route); }
  public static class staticFiles { public static void location(String dir) { staticBase = new File(dir); } }

  private static synchronized void ensureStarted() {
    if (started) return;
    started = true;
    if (httpsPort != null) pool.submit(() -> serverLoopTLS(httpsPort));
    pool.submit(() -> serverLoopHTTP(httpPort));
  }

  private static void addRoute(String method, String path, Route route) {
    routes.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>()).add(new RouteEntry(path, route));
    ensureStarted();
  }

  private static void serverLoopHTTP(int port) {
    try (ServerSocket ss = new ServerSocket(port)) {
      while (true) {
        Socket s = ss.accept();
        try { s.setTcpNoDelay(true); } catch (Exception ignored) {}
        pool.submit(() -> handleConnection(s, false));
      }
    } catch (Exception e) {}
  }

  private static void serverLoopTLS(int port) {
    try {
      String pwd = "secret";
      KeyStore keyStore = KeyStore.getInstance("JKS");
      try (FileInputStream fis = new FileInputStream("keystore.jks")) { keyStore.load(fis, pwd.toCharArray()); }
      KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
      kmf.init(keyStore, pwd.toCharArray());
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), null, null);
      SSLServerSocketFactory factory = ctx.getServerSocketFactory();
      try (ServerSocket ss = factory.createServerSocket(port)) {
        while (true) {
          Socket s = ss.accept();
          try { s.setTcpNoDelay(true); } catch (Exception ignored) {}
          pool.submit(() -> handleConnection(s, true));
        }
      }
    } catch (Exception e) {}
  }

  private static void handleConnection(Socket socket, boolean isTLS) {
    try (InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
      BufferedInputStream bin = new BufferedInputStream(in);
      ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
      while (true) {
        int b = bin.read();
        if (b == -1) return;
        headerBuf.write(b);
        byte[] arr = headerBuf.toByteArray();
        int n = arr.length;
        if (n >= 4 && arr[n-4]=='\r' && arr[n-3]=='\n' && arr[n-2]=='\r' && arr[n-1]=='\n') break;
      }
      byte[] headerBytes = headerBuf.toByteArray();
      String headerStr = new String(headerBytes, StandardCharsets.UTF_8);
      String[] headerLines = headerStr.split("\r\n");
      if (headerLines.length == 0) return;
      String[] reqLine = headerLines[0].split(" ");
      if (reqLine.length < 3) { writeSimple(out, 400, "Bad Request"); return; }
      String method = reqLine[0];
      String target = reqLine[1];
      String version = reqLine[2];
      Map<String,List<String>> headersList = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      for (int i=1;i<headerLines.length;i++) {
        String line = headerLines[i];
        if (line.isEmpty()) continue;
        int idx = line.indexOf(':');
        if (idx>0) {
          String k = line.substring(0,idx).trim();
          String v = line.substring(idx+1).trim();
          headersList.computeIfAbsent(k, kk -> new ArrayList<>()).add(v);
        }
      }
      int contentLength = 0;
      String cl = null;
        List<String> clVals = headersList.get("Content-Length");
        if (clVals == null) clVals = headersList.get("content-length");
        if (clVals != null && !clVals.isEmpty()) {
            cl = clVals.get(0);
        }
      if (cl != null) { try { contentLength = Integer.parseInt(cl); } catch (Exception ignored) {} }
      byte[] body = new byte[contentLength];
      int read = 0;
      while (read < contentLength) {
        int r = bin.read(body, read, contentLength - read);
        if (r<0) break;
        read += r;
      }
      ResponseImpl res = new ResponseImpl(isTLS, out);
      RequestImpl req = new RequestImpl(socket, method, target, version, headersList, body, res, isTLS);
      Object result = dispatch(method, req.url(), req, res);
      if (!res.committed) {
        if (res.bodyBytes == null) {
          if (result != null) {
            byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
            if (!res.sentType) res.type("text/plain; charset=utf-8");
            res.bodyAsBytes(bytes);
          } else {
            res.bodyAsBytes(new byte[0]);
          }
        }
        res.sendBuffered();
      }
    } catch (Exception e) {
    } finally {
      try { socket.close(); } catch (Exception ignored) {}
    }
  }

  private static void writeSimple(OutputStream out, int code, String message) throws IOException {
    String body = code + " " + message;
    String hdr = "HTTP/1.1 " + code + " " + message + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n";
    out.write(hdr.getBytes(StandardCharsets.UTF_8));
    out.write(body.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static Object dispatch(String method, String path, RequestImpl req, ResponseImpl res) throws Exception {
    if (staticBase != null && ("GET".equals(method) || "HEAD".equals(method))) {
      File f = mapToFile(path);
      if (f != null && f.isFile() && f.canRead()) {
        byte[] data = readAllBytes(f);
        String mime = guessMime(f.getName());
        res.type(mime);
        if ("HEAD".equals(method)) return "";
        return new String(data, StandardCharsets.ISO_8859_1);
      }
    }
    List<RouteEntry> list = routes.getOrDefault(method, Collections.emptyList());
    for (RouteEntry re : list) {
      Matcher m = re.regex.matcher(path);
      if (m.matches()) {
        Map<String,String> params = new LinkedHashMap<>();
        for (int i=0;i<re.paramNames.size();i++) params.put(re.paramNames.get(i), urlDecode(m.group(i+1)));
        req.pathParams = params;
        return re.route.handle(req, res);
      }
    }
    res.status(404, "Not Found");
    return "Not Found";
  }

  private static File mapToFile(String path) {
    if (staticBase == null) return null;
    String p = path;
    int q = p.indexOf('?');
    if (q>=0) p = p.substring(0,q);
    p = p.replace('/', File.separatorChar);
    if (p.startsWith(File.separator)) p = p.substring(1);
    File f = new File(staticBase, p);
    try {
      String base = staticBase.getCanonicalPath();
      String full = f.getCanonicalPath();
      if (!full.startsWith(base)) return null;
    } catch (IOException e) { return null; }
    if (f.isDirectory()) f = new File(f, "index.html");
    return f;
  }

  private static byte[] readAllBytes(File f) throws IOException {
    try (FileInputStream fis = new FileInputStream(f)) {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int r;
      while ((r = fis.read(buf)) != -1) bos.write(buf, 0, r);
      return bos.toByteArray();
    }
  }

  private static String guessMime(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
    if (n.endsWith(".css")) return "text/css; charset=utf-8";
    if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
    if (n.endsWith(".json")) return "application/json; charset=utf-8";
    if (n.endsWith(".png")) return "image/png";
    if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
    if (n.endsWith(".gif")) return "image/gif";
    if (n.endsWith(".svg")) return "image/svg+xml";
    if (n.endsWith(".txt")) return "text/plain; charset=utf-8";
    return "application/octet-stream";
  }

  private static String urlDecode(String s) {
    try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
  }

  private static final class RouteEntry {
    final Pattern regex;
    final List<String> paramNames;
    final Route route;
    RouteEntry(String pathPattern, Route route) {
      this.paramNames = new ArrayList<>();
      StringBuilder sb = new StringBuilder();
      String[] parts = pathPattern.split("/");
      sb.append("^");
      for (String part : parts) {
        if (part.isEmpty()) continue;
        sb.append("/");
        if (part.startsWith(":")) {
          String name = part.substring(1);
          paramNames.add(name);
          sb.append("([^/]+)");
        } else {
          sb.append(Pattern.quote(part));
        }
      }
      if (pathPattern.endsWith("/")) sb.append("/");
      sb.append("$");
      this.regex = Pattern.compile(sb.toString());
      this.route = route;
    }
  }

  private static class RequestImpl implements Request {
    final Socket socket;
    final String method;
    final String target;
    final String version;
    final Map<String,List<String>> rawHeaders;
    final Map<String,String> lcHeaders;
    final byte[] body;
    final ResponseImpl res;
    final boolean isTLS;
    Map<String,String> queryParams;
    Map<String,String> pathParams = new LinkedHashMap<>();
    SessionImpl boundSession;
    boolean sessionCreated = false;

    RequestImpl(Socket socket, String method, String target, String version, Map<String,List<String>> headers, byte[] body, ResponseImpl res, boolean isTLS) {
      this.socket = socket;
      this.method = method;
      this.target = target;
      this.version = version;
      this.rawHeaders = headers;
      this.lcHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      for (Map.Entry<String,List<String>> e : headers.entrySet()) {
        String k = e.getKey().toLowerCase(Locale.ROOT);
        String v = String.join(", ", e.getValue());
        lcHeaders.put(k, v);
      }
      this.body = body;
      this.res = res;
      this.isTLS = isTLS;
      int q = target.indexOf('?');
      String qs = q>=0 ? target.substring(q+1) : "";
      queryParams = parseQueryParams(qs);
      if ("application/x-www-form-urlencoded".equalsIgnoreCase(contentType())) {
        String bodyStr = new String(body, StandardCharsets.UTF_8);
        Map<String,String> m = parseQueryParams(bodyStr);
        if (!m.isEmpty()) {
          for (Map.Entry<String,String> e : m.entrySet()) {
            if (queryParams.containsKey(e.getKey())) queryParams.put(e.getKey(), queryParams.get(e.getKey()) + "," + e.getValue());
            else queryParams.put(e.getKey(), e.getValue());
          }
        }
      }
      String cookie = lcHeaders.get("cookie");
      if (cookie != null) {
        for (String c : cookie.split(";")) {
          String[] kv = c.trim().split("=",2);
          if (kv.length==2 && kv[0].trim().equals("SessionID")) {
            String sid = kv[1].trim();
            SessionImpl s = sessions.get(sid);
            if (s != null && !s.isExpired(System.currentTimeMillis())) {
              s.touch();
              boundSession = s;
            }
          }
        }
      }
    }

    private Map<String,String> parseQueryParams(String qs) {
      Map<String,String> m = new LinkedHashMap<>();
      if (qs == null || qs.isEmpty()) return m;
      for (String kv : qs.split("&")) {
        if (kv.isEmpty()) continue;
        String[] p = kv.split("=",2);
        String k = urlDecode(p[0]);
        String v = p.length>1 ? urlDecode(p[1]) : "";
        if (m.containsKey(k)) m.put(k, m.get(k) + "," + v); else m.put(k, v);
      }
      return m;
    }

    @Override public String ip() { return socket.getInetAddress().getHostAddress(); }
    @Override public int port() { return socket.getPort(); }
    @Override public String requestMethod() { return method; }
    @Override public String url() { int q = target.indexOf('?'); return q>=0 ? target.substring(0,q) : target; }
    @Override public String protocol() { return version; }
    @Override public Set<String> headers() { return new TreeSet<>(lcHeaders.keySet()); }
    @Override public String headers(String name) { return lcHeaders.get(name==null?null:name.toLowerCase(Locale.ROOT)); }
    @Override public String contentType() { return lcHeaders.get("content-type"); }
    @Override public String body() { return new String(body, StandardCharsets.UTF_8); }
    @Override public byte[] bodyAsBytes() { return body==null? new byte[0] : body; }
    @Override public int contentLength() { return body==null? 0 : body.length; }
    @Override public Set<String> queryParams() { return queryParams.keySet(); }
    @Override public String queryParams(String param) { return queryParams.get(param); }
    @Override public Map<String,String> params() { return pathParams; }
    @Override public String params(String name) { return pathParams.get(name); }
    @Override public Session session() {
      if (boundSession != null) return boundSession;
      String sid = generateSessionId();
      SessionImpl s = new SessionImpl(sid);
      sessions.put(sid, s);
      boundSession = s;
      sessionCreated = true;
      StringBuilder sb = new StringBuilder();
      sb.append("SessionID=").append(sid).append("; Path=/; HttpOnly");
      if (isTLS) sb.append("; Secure");
      res.header("Set-Cookie", sb.toString());
      return s;
    }
  }

  private static class ResponseImpl implements Response {
    int statusCode = 200;
    String statusMsg = "OK";
    final Map<String,String> headers = new LinkedHashMap<>();
    byte[] bodyBytes;
    boolean committed = false;
    boolean sentType = false;
    final boolean isTLS;
    final OutputStream out;
    ResponseImpl(boolean isTLS, OutputStream out) { this.isTLS = isTLS; this.out = out; }
    @Override public void body(String body) { this.bodyBytes = body==null? null : body.getBytes(StandardCharsets.UTF_8); }
    @Override public void bodyAsBytes(byte[] bodyArg) { this.bodyBytes = bodyArg; }
    @Override public void header(String name, String value) { headers.put(name, value); }
    @Override public void type(String contentType) { headers.put("Content-Type", contentType); sentType = true; }
    @Override public void status(int statusCode, String reasonPhrase) { this.statusCode=statusCode; this.statusMsg=reasonPhrase; }
    @Override public void write(byte[] b) throws Exception {
      if (!committed) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusMsg).append("\r\n");
        for (Map.Entry<String,String> e : headers.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        committed = true;
      }
      if (b != null && b.length>0) out.write(b);
      out.flush();
    }
    public void sendBuffered() throws IOException {
      if (committed) return;
      if (!headers.containsKey("Content-Type")) headers.put("Content-Type","text/plain; charset=utf-8");
      if (bodyBytes == null) bodyBytes = new byte[0];
      headers.put("Content-Length", Integer.toString(bodyBytes.length));
      StringBuilder sb = new StringBuilder();
      sb.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusMsg).append("\r\n");
      for (Map.Entry<String,String> e : headers.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
      sb.append("Connection: close\r\n\r\n");
      out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
      out.write(bodyBytes);
      out.flush();
      committed = true;
    }
    @Override public void redirect(String url, int responseCode) {}
    @Override public void halt(int statusCode, String reasonPhrase) {}
  }

  private static class SessionImpl implements Session {
    final String id;
    final long created;
    volatile long lastAccessed;
    volatile int maxInterval = 300;
    final ConcurrentHashMap<String,Object> data = new ConcurrentHashMap<>();
    SessionImpl(String id) {
      this.id = id;
      this.created = System.currentTimeMillis();
      this.lastAccessed = created;
    }
    @Override public String id() { return id; }
    @Override public long creationTime() { return created; }
    @Override public long lastAccessedTime() { return lastAccessed; }
    @Override public void maxActiveInterval(int seconds) { this.maxInterval = seconds; }
    @Override public void invalidate() { sessions.remove(id); }
    @Override public Object attribute(String name) { return data.get(name); }
    @Override public void attribute(String name, Object value) { if (value==null) data.remove(name); else data.put(name, value); }
    boolean isExpired(long now) { return (now - lastAccessed) > (maxInterval * 1000L); }
    void touch() { this.lastAccessed = System.currentTimeMillis(); }
  }

  public static void start() {}

  public static void halt(int status, String body) { throw new RuntimeException(); }

  private static String generateSessionId() {
    String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_";
    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    StringBuilder sb = new StringBuilder();
    for (int i=0;i<20;i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
    return sb.toString();
  }
}
