package cis5550.webserver;

import cis5550.tools.Logger;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class Server {
	
	private static final Logger logger = Logger.getLogger(Server.class);

	public static void main(String[] args) {
		if (args.length != 2) {
			System.out.println("Written by Vivian Chiang");
			return;
		}
		
		final int port;
	    try {
	      port = Integer.parseInt(args[0]);
	    } catch (NumberFormatException e) {
	      System.out.println("Written by Vivian Chiang");
	      return;
	    }
	    
	    final Path basePath = Paths.get(args[1]).toAbsolutePath().normalize();
	    if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
	      System.err.println("Root directory does not exist");
	      return;
	    }
	    
	    try (ServerSocket server = new ServerSocket(port)) {
	        server.setReuseAddress(true);
	        logger.info("Listening on port " + port + " root=" + basePath);
	        while (true) {
	          final Socket sock = server.accept();
	          Thread t = new Thread(new Worker(sock, basePath));
	          t.setDaemon(true);
	          t.start();
	        }
	      } catch (IOException e) {
	    	  logger.error("Fatal I/O error in accept loop", e);
	      }

	}
	
	private static class Worker implements Runnable {
	    private final Socket sock;
	    private final Path base;

	    Worker(Socket s, Path base) {
	      this.sock = s;
	      this.base = base;
	    }

	    @Override
	    public void run() {
	      try (InputStream in = sock.getInputStream();
	           OutputStream rawOut = sock.getOutputStream();
	           BufferedOutputStream out = new BufferedOutputStream(rawOut)) {

	        ByteArrayOutputStream buf = new ByteArrayOutputStream();
	        byte[] temp = new byte[8192];

	        boolean open = true;
	        while (open) {
	          int headersEnd = indexOfHeadersEnd(buf.toByteArray());
	          while (headersEnd < 0) {
	            int r = in.read(temp);
	            if (r == -1) { open = false; break; }
	            buf.write(temp, 0, r);
	            headersEnd = indexOfHeadersEnd(buf.toByteArray());
	          }
	          if (!open) break;

	          byte[] all = buf.toByteArray();
	          int bodyStart = headersEnd + 4;

	          Map<String,String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	          String requestLine;
	          try {
	            String headersStr = new String(all, 0, headersEnd, java.nio.charset.StandardCharsets.ISO_8859_1);
	            BufferedReader br = new BufferedReader(new StringReader(headersStr));
	            requestLine = br.readLine();
	            String h;
	            while ((h = br.readLine()) != null) {
	              int colon = h.indexOf(':');
	              if (colon > 0) {
	                String name = h.substring(0, colon).trim();
	                String val  = h.substring(colon + 1).trim();
	                headers.put(name, val);
	              }
	            }
	          } catch (Exception parseEx) {
	            writeError(out, "HTTP/1.1", 400, "Bad Request");
	            logger.warn("Malformed headers from " + sock.getRemoteSocketAddress(), parseEx);
	            out.flush();
	            buf.reset();
	            continue;
	          }

	          if (requestLine == null || requestLine.isEmpty()) {
	        	writeError(out, "HTTP/1.1", 400, "Bad Request");
	            logger.warn("Empty request line");
	            out.flush();
	            buf.reset();
	            continue;
	          }

	          String[] parts = requestLine.split("\\s+");
	          if (parts.length != 3) {
	            writeError(out, "HTTP/1.1", 400, "Bad Request");
	            logger.warn("Bad request line: '" + requestLine + "'");
	            out.flush();
	            buf.reset();
	            continue;
	          }

	          String method = parts[0];
	          String target = parts[1];
	          String version = parts[2];
	          logger.debug("Request line: " + requestLine);

	          if (!"HTTP/1.1".equals(version)) {
	        	  writeError(out, "HTTP/1.1", 505, "HTTP Version Not Supported");
	        	  out.flush();
	          }

	          if (!headers.containsKey("Host")) {
	        	  writeError(out, version, 400, "Bad Request");
	        	  out.flush();
	          }

	          long contentLen = 0;
	          try {
	            if (headers.containsKey("Content-Length")) {
	              contentLen = Long.parseLong(headers.get("Content-Length"));
	              if (contentLen < 0) contentLen = 0;
	            }
	          } catch (NumberFormatException ignored) { contentLen = 0; }

	          long bytesAlready = all.length - bodyStart;
	          while (bytesAlready < contentLen) {
	            int r = in.read(temp);
	            if (r == -1) { open = false; break; }
	            buf.write(temp, 0, r);
	            all = buf.toByteArray();
	            bytesAlready = all.length - bodyStart;
	          }
	          if (!open) break;
	          
	          int statusToWrite = 200;
	          boolean handled = true;

	          if (!"HTTP/1.1".equals(version)) {
	            statusToWrite = 505;
	            handled = false;
	          } else if ("GET".equals(method) || "HEAD".equals(method)) {
	            if (target.contains("..")) {
	              writeError(out, version, 403, "Forbidden");
	            } else {
	              String clean = target.startsWith("/") ? target.substring(1) : target;
	              Path file = base.resolve(clean).normalize();

	              if (!file.startsWith(base)) {
	                writeError(out, version, 403, "Forbidden");
	              } else if (!Files.exists(file)) {
	                writeError(out, version, 404, "Not Found");
	              } else if (!Files.isReadable(file) || Files.isDirectory(file)) {
	                writeError(out, version, 403, "Forbidden");
	              } else {
	                try {
	                  long len = Files.size(file);
	                  String ctype = guessContentType(file);

	                  StringBuilder sb = new StringBuilder();
	                  sb.append(version).append(" 200 OK").append("\r\n");
	                  sb.append("Server: ").append("HW1Server").append("\r\n");
	                  sb.append("Content-Type: ").append(ctype).append("\r\n");
	                  sb.append("Content-Length: ").append(len).append("\r\n");
	                  sb.append("\r\n");
	                  out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
	                  out.flush();

	                  if ("GET".equals(method)) {
	                    try (InputStream fis = Files.newInputStream(file)) {
	                      byte[] fbuf = new byte[8192];
	                      int n;
	                      while ((n = fis.read(fbuf)) != -1) {
	                        out.write(fbuf, 0, n);
	                      }
	                    }
	                  }
	                } catch (IOException ioe) {
	                  writeError(out, version, 403, "Forbidden");
	                }
	              }
	            }
	          } else if ("POST".equals(method) || "PUT".equals(method)) {
	            writeError(out, version, 405, "Not Allowed");
	          } else {
	            writeError(out, version, 501, "Not Implemented");
	          }

	          out.flush();

	          int consumed = (int)(bodyStart + contentLen);
	          int remain = all.length - consumed;
	          if (remain > 0) {
	            ByteArrayOutputStream next = new ByteArrayOutputStream(Math.max(remain, 128));
	            next.write(all, consumed, remain);
	            buf = next;
	          } else {
	            buf.reset();
	          }
	        }

	      } catch (IOException ignored) {
	      } finally {
	        try { sock.close(); } catch (IOException ignored) {}
	      }
	    }

	    private static int indexOfHeadersEnd(byte[] a) {
	      for (int i = 0; i + 3 < a.length; i++) {
	        if (a[i] == 13 && a[i+1] == 10 && a[i+2] == 13 && a[i+3] == 10) {
	          return i;
	        }
	      }
	      return -1;
	    }

	    private static void writeError(BufferedOutputStream out, String version, int code, String msg) throws IOException {
	      String body = code + " " + msg + "\n";
	      StringBuilder sb = new StringBuilder();
	      sb.append((version != null && !version.isEmpty()) ? version : "HTTP/1.1")
	        .append(" ").append(code).append(" ").append(msg).append("\r\n");
	      sb.append("Server: ").append("HW1Server").append("\r\n");
	      sb.append("Content-Type: text/plain\r\n");
	      sb.append("Content-Length: ").append(body.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1).length).append("\r\n");
	      sb.append("\r\n");
	      out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
	      out.write(body.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
	    }

	    private static String guessContentType(Path file) {
	      String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
	      if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
	      if (name.endsWith(".txt")) return "text/plain";
	      if (name.endsWith(".html")) return "text/html";
	      return "application/octet-stream";
	    }
	  }
	}
