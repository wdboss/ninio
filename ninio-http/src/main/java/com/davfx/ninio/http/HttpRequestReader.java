package com.davfx.ninio.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.ninio.core.Address;
import com.davfx.ninio.core.ByteBufferHandler;
import com.davfx.ninio.core.CloseableByteBufferHandler;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;

final class HttpRequestReader implements CloseableByteBufferHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger(HttpRequestReader.class);

	private final LineReader lineReader = new LineReader();
	private boolean headersRead = false;
	private boolean requestLineRead = false;
	private long contentLength;
	private int countRead = 0;
	private HttpMethod requestMethod;
	private String requestPath;
	private boolean http11;
	private boolean enableGzip = false;
	private final Multimap<String, String> headers = HashMultimap.create();
	private boolean failClose = false;
	private boolean closed = false;
	private final Address address;
	private final boolean secure;

	private final HttpServerHandler handler;
	private final CloseableByteBufferHandler write;
	
	private final Map<String, String> headerSanitization = new HashMap<String, String>();
	
	public HttpRequestReader(Address address, boolean secure, HttpServerHandler handler, CloseableByteBufferHandler write) {
		this.address = address;
		this.secure = secure;
		this.handler = handler;
		this.write = write;

		headerSanitization.put(HttpHeaderKey.CONTENT_LENGTH.toLowerCase(), HttpHeaderKey.CONTENT_LENGTH);
		headerSanitization.put(HttpHeaderKey.CONTENT_ENCODING.toLowerCase(), HttpHeaderKey.CONTENT_ENCODING);
		headerSanitization.put(HttpHeaderKey.CONTENT_TYPE.toLowerCase(), HttpHeaderKey.CONTENT_TYPE);
		headerSanitization.put(HttpHeaderKey.ACCEPT_ENCODING.toLowerCase(), HttpHeaderKey.ACCEPT_ENCODING);
		headerSanitization.put(HttpHeaderKey.TRANSFER_ENCODING.toLowerCase(), HttpHeaderKey.TRANSFER_ENCODING);
	}
	
	private void addHeader(String headerLine) throws IOException {
		int i = headerLine.indexOf(HttpSpecification.HEADER_KEY_VALUE_SEPARATOR);
		if (i < 0) {
			throw new IOException("Invalid header: " + headerLine);
		}
		String key = headerLine.substring(0, i);
		String sanitizedKey = headerSanitization.get(key.toLowerCase());
		if (sanitizedKey != null) {
			key = sanitizedKey;
		}
		String value = headerLine.substring(i + 1).trim();
		headers.put(key, value);
	}
	private void setRequestLine(String requestLine) throws IOException {
		int i = requestLine.indexOf(HttpSpecification.START_LINE_SEPARATOR);
		if (i < 0) {
			throw new IOException("Invalid request: " + requestLine);
		}
		int j = requestLine.indexOf(HttpSpecification.START_LINE_SEPARATOR, i + 1);
		if (j < 0) {
			throw new IOException("Invalid request: " + requestLine);
		}
		requestMethod = null;
		String m = requestLine.substring(0, i);
		for (HttpMethod method : HttpMethod.values()) {
			if (method.toString().equals(m)) {
				requestMethod = method;
				break;
			}
		}
		if (requestMethod == null) {
			throw new IOException("Invalid request: " + requestLine);
		}
		requestPath = requestLine.substring(i + 1, j);
		String requestVersion = requestLine.substring(j + 1);
		if (requestVersion.equals(HttpSpecification.HTTP10)) {
			http11 = false;
		} else if (requestVersion.equals(HttpSpecification.HTTP11)) {
			http11 = true;
		} else {
			throw new IOException("Unsupported version");
		}
	}
	
	@Override
	public void close() {
		LOGGER.debug("Closing");
		if (failClose) {
			if (!closed) {
				closed = true;
				handler.failed(new IOException("Connection reset by peer"));
			}
		} else {
			if (!closed) {
				closed = true;
				handler.close();
			}
		}
	}
	
	@Override
	public void handle(Address address, ByteBuffer buffer) {
		if (!buffer.hasRemaining()) {
			return;
		}
		try {
			failClose = true;
			
			while (!requestLineRead) {
				String line = lineReader.handle(buffer);
				if (line == null) {
					return;
				}
				LOGGER.trace("Request line: {}", line);
				setRequestLine(line);
				requestLineRead = true;
			}
	
			while (!headersRead) {
				String line = lineReader.handle(buffer);
				if (line == null) {
					return;
				}
				if (line.isEmpty()) {
					LOGGER.trace("Header line empty");
					headersRead = true;

					for (String accept : headers.get(HttpHeaderKey.ACCEPT_ENCODING)) {
						String[] list = accept.split("\\" + HttpSpecification.MULTIPLE_SEPARATOR);
						for (String s : list) {
							String[] v = s.trim().split("\\" + HttpSpecification.EXTENSION_SEPARATOR);
							if (v.length > 0) {
								if (v[0].trim().equalsIgnoreCase(HttpHeaderValue.GZIP)) {
									enableGzip = true;
									break;
								}
							}
						}
						if (enableGzip) {
							break;
						}
					}
					
					handler.handle(new HttpRequest(address, secure, requestMethod, new HttpPath(requestPath), ImmutableMultimap.copyOf(headers)));
					
					contentLength = 0;
					for (String contentLengthValue : headers.get(HttpHeaderKey.CONTENT_LENGTH)) {
						try {
							contentLength = Long.parseLong(contentLengthValue);
						} catch (NumberFormatException e) {
							throw new IOException("Invalid Content-Length: " + contentLengthValue);
						}
						break;
					}

					if (contentLength == 0) {
						handler.ready(new InnerWrite()); // Yes, can be so cool for ws://
					}
				} else {
					LOGGER.trace("Header line: {}", line);
					addHeader(line);
				}
			}

			if (contentLength == 0) {
				if (buffer.hasRemaining()) {
					handler.handle(null, buffer);
				}
			} else {
				if (countRead < contentLength) {
					if (buffer.hasRemaining()) {
						ByteBuffer d = buffer;
						long toRead = contentLength - countRead;
						if (buffer.remaining() > toRead) {
							d = buffer.duplicate();
							d.limit((int) (buffer.position() + toRead));
							buffer.position((int) (buffer.position() + toRead));
						}
						countRead += d.remaining();
						handler.handle(null, d);
					}
				}
		
				if (countRead == contentLength) {
					failClose = false;
					countRead = 0;
					requestLineRead = false; // another connection possible
					headersRead = false;
					handler.ready(new InnerWrite());
				}
			}
		} catch (IOException e) {
			if (!closed) {
				closed = true;
				write.close();
				handler.failed(e);
			}
		}
	}
	
	private final class InnerWrite implements HttpServerHandler.Write {
		private long countWrite = 0;
		private long writeContentLength = -1;
		private boolean chunked = false;
		private GzipWriter gzipWriter = null;
		private boolean innerClosed = false;
		
		public InnerWrite() {
		}
		
		@Override
		public void close() {
			if (innerClosed) {
				return;
			}
			
			innerClosed = true;
			
			if (gzipWriter != null) {
				gzipWriter.close();
			}
			
			if (http11) {
				if (chunked) {
					write.handle(address, LineReader.toBuffer(Integer.toHexString(0)));
					write.handle(address, LineReader.toBuffer(""));
					return;
				}
				
				if ((writeContentLength >= 0) && (countWrite == writeContentLength)) {
					failClose = false;
					countRead = 0;
					requestLineRead = false; // another connection possible
					headersRead = false;
					return; // keep alive
				}
			}
			
			closed = true;
			
			write.close();
		}
		
		@Override
		public void failed(IOException error) {
			if (innerClosed) {
				return;
			}
			
			closed = true;
			write.close();
		}

		@Override
		public void write(HttpResponse response) {
			if (innerClosed) {
				return;
			}
			
			if (http11) {
				if (enableGzip) {
					for (String contentEncodingValue : response.headers.get(HttpHeaderKey.CONTENT_ENCODING)) {
						if (HttpHeaderValue.GZIP.equalsIgnoreCase(contentEncodingValue)) {
							gzipWriter = new GzipWriter(new ByteBufferHandler() {
								@Override
								public void handle(Address address, ByteBuffer buffer) {
									doWrite(buffer);
								}
							});
							break;
						}
					}
				}
				
				if (gzipWriter == null) {
					for (String transferEncodingValue : response.headers.get(HttpHeaderKey.TRANSFER_ENCODING)) {
						chunked = HttpHeaderValue.CHUNKED.equalsIgnoreCase(transferEncodingValue);
						break;
					}
				} else {
					chunked = true;
					// deflated length != source length
				}
			}
			
			for (String contentLengthValue : response.headers.get(HttpHeaderKey.CONTENT_LENGTH)) {
				try {
					writeContentLength = Integer.parseInt(contentLengthValue);
					break;
				} catch (NumberFormatException e) {
				}
			}
			// Don't fallback anymore in case of no content length, because of websockets // if (writeContentLength == -1) { chunked = true; }
			
			write.handle(null, LineReader.toBuffer((http11 ? HttpSpecification.HTTP11 : HttpSpecification.HTTP10) + HttpSpecification.START_LINE_SEPARATOR + response.status + HttpSpecification.START_LINE_SEPARATOR + response.reason));
			for (Map.Entry<String, String> h : response.headers.entries()) {
				String k = h.getKey();
				String v = h.getValue();
				if (gzipWriter != null) {
					if (k.equals(HttpHeaderKey.CONTENT_LENGTH)) {
						continue;
					}
					if (k.equals(HttpHeaderKey.TRANSFER_ENCODING)) {
						continue;
					}
				}
				if (!http11) {
					if (k.equals(HttpHeaderKey.CONTENT_ENCODING)) {
						continue;
					}
				}
				write.handle(null, LineReader.toBuffer(k + HttpSpecification.HEADER_KEY_VALUE_SEPARATOR + HttpSpecification.HEADER_BEFORE_VALUE + v));
			}
			if (chunked) {
				write.handle(null, LineReader.toBuffer(HttpHeaderKey.TRANSFER_ENCODING + HttpSpecification.HEADER_KEY_VALUE_SEPARATOR + HttpSpecification.HEADER_BEFORE_VALUE + HttpHeaderValue.CHUNKED));
			}
			write.handle(null, LineReader.toBuffer(""));
		}
		
		@Override
		public void handle(Address address, ByteBuffer buffer) {
			if (innerClosed) {
				return;
			}
			
			if (gzipWriter != null) {
				gzipWriter.handle(buffer);
			} else {
				doWrite(buffer);
			}
		}
		
		private void doWrite(ByteBuffer buffer) {
			if (!buffer.hasRemaining()) {
				return;
			}
			if (chunked) {
				write.handle(null, LineReader.toBuffer(Integer.toHexString(buffer.remaining())));
			}
			countWrite += buffer.remaining();
			write.handle(null, buffer);
			if (chunked) {
				write.handle(null, LineReader.toBuffer(""));
			}
		}
	}
	
}