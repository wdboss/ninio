package com.davfx.ninio.http.v3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.ninio.core.Address;
import com.davfx.ninio.core.v3.Closing;
import com.davfx.ninio.core.v3.Connector;
import com.davfx.ninio.core.v3.Disconnectable;
import com.davfx.ninio.core.v3.Failing;
import com.davfx.ninio.core.v3.NinioBuilder;
import com.davfx.ninio.core.v3.Queue;
import com.davfx.ninio.core.v3.Receiver;
import com.davfx.ninio.core.v3.SslSocketBuilder;
import com.davfx.ninio.core.v3.TcpSocket;
import com.davfx.ninio.http.HttpHeaderKey;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public final class HttpClient implements Disconnectable, AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(HttpClient.class);
	
	private static final Config CONFIG = ConfigFactory.load(HttpClient.class.getClassLoader());
	private static final int DEFAULT_MAX_REDIRECTIONS = CONFIG.getInt("ninio.http.redirect.max");

	private static final String DEFAULT_USER_AGENT = "ninio"; // Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/38.0.2125.111 Safari/537.36";
	private static final String DEFAULT_ACCEPT = "*/*";

	public static interface Builder extends NinioBuilder<HttpClient> {
		Builder pipelining();
		Builder with(Executor executor);
		Builder with(TcpSocket.Builder connectorFactory);
		Builder withSecure(TcpSocket.Builder secureConnectorFactory);
	}
	
	public static Builder builder() {
		return new Builder() {
			private Executor executor = null;
			private TcpSocket.Builder connectorFactory = TcpSocket.builder();
			private TcpSocket.Builder secureConnectorFactory = new SslSocketBuilder(TcpSocket.builder());
			private boolean pipelining = false;
			
			@Override
			public Builder pipelining() {
				pipelining = true;
				return this;
			}
			
			@Override
			public Builder with(Executor executor) {
				this.executor = executor;
				return this;
			}
			
			@Override
			public Builder with(TcpSocket.Builder connectorFactory) {
				this.connectorFactory = connectorFactory;
				return this;
			}

			@Override
			public Builder withSecure(TcpSocket.Builder secureConnectorFactory) {
				this.secureConnectorFactory = secureConnectorFactory;
				return this;
			}

			@Override
			public HttpClient create(Queue queue) {
				if (executor == null) {
					throw new NullPointerException("executor");
				}
				return new HttpClient(queue, executor, connectorFactory, secureConnectorFactory, pipelining);
			}
		};
	}
	
	private final Executor executor;
	private final TcpSocket.Builder connectorFactory;
	private final TcpSocket.Builder secureConnectorFactory;
	
	private interface ClosingFailingReceiver extends Closing, Failing, Receiver {
	}

	private static final class ReusableConnector {
		public final Connector connector;
		public final boolean secure;

		public boolean reusable = true;

		private ClosingFailingReceiver receiver = null;
		private final Deque<ClosingFailingReceiver> nextReceivers = new LinkedList<>();
		
		public ReusableConnector(TcpSocket.Builder factory, Queue queue, boolean secure) {
			this.secure = secure;
			
			factory.receiving(new Receiver() {
				@Override
				public void received(Connector connector, Address address, ByteBuffer buffer) {
					if (receiver != null) {
						receiver.received(connector, null, buffer);
					}
				}
			});
			
			factory.closing(new Closing() {
				@Override
				public void closed() {
					if (receiver != null) {
						receiver.closed();
					}
				}
			});

			connector = factory.create(queue);
		}
		
		public void failNext(IOException ioe) {
			IOException e = new IOException("Error in pipeline", ioe);
			for (ClosingFailingReceiver r : nextReceivers) {
				r.failed(e);
			}
		}
		
		public void push(ClosingFailingReceiver receiver) {
			if (this.receiver == null) {
				this.receiver = receiver;
			} else {
				nextReceivers.addLast(receiver);
			}
		}
		
		public void pop() {
			if (!nextReceivers.isEmpty()) {
				receiver = nextReceivers.removeFirst();
			} else {
				receiver = null;
			}
		}
	}
	
	private final Queue queue;
	
	private final Map<Long, ReusableConnector> reusableConnectors = new HashMap<>();
	private long nextReusableConnectorId = 0L;
	
	private final boolean pipelining;
	
	private final ByteBuffer emptyLineByteBuffer = LineReader.toBuffer("");
	
	private final Map<String, String> headerSanitization = new HashMap<String, String>();
	
	{
		headerSanitization.put(HttpHeaderKey.CONTENT_LENGTH.toLowerCase(), HttpHeaderKey.CONTENT_LENGTH);
		headerSanitization.put(HttpHeaderKey.CONTENT_ENCODING.toLowerCase(), HttpHeaderKey.CONTENT_ENCODING);
		headerSanitization.put(HttpHeaderKey.TRANSFER_ENCODING.toLowerCase(), HttpHeaderKey.TRANSFER_ENCODING);
		headerSanitization.put(HttpHeaderKey.CONNECTION.toLowerCase(), HttpHeaderKey.CONNECTION);
		headerSanitization.put(HttpHeaderKey.ACCEPT_ENCODING.toLowerCase(), HttpHeaderKey.ACCEPT_ENCODING);

		headerSanitization.put(HttpHeaderKey.HOST.toLowerCase(), HttpHeaderKey.HOST);
		headerSanitization.put(HttpHeaderKey.USER_AGENT.toLowerCase(), HttpHeaderKey.USER_AGENT);
		headerSanitization.put(HttpHeaderKey.ACCEPT.toLowerCase(), HttpHeaderKey.ACCEPT);
	}

	private HttpClient(Queue queue, Executor executor, TcpSocket.Builder connectorFactory, TcpSocket.Builder secureConnectorFactory, boolean pipelining) {
		this.queue = queue;
		this.executor = executor;
		this.connectorFactory = connectorFactory;
		this.secureConnectorFactory = secureConnectorFactory;
		this.pipelining = pipelining;
	}

	@Override
	public void close() {
		executor.execute(new Runnable() {
			@Override
			public void run() {
				for (ReusableConnector connector : reusableConnectors.values()) {
					connector.connector.close();
				}
				reusableConnectors.clear();
			}
		});
	}

	public HttpReceiverRequestBuilder request() {
		return new HttpReceiverRequestBuilder() {
			private HttpReceiver receiver = null;
			private Failing failing = null;
			private int maxRedirections = DEFAULT_MAX_REDIRECTIONS;

			@Override
			public HttpReceiverRequestBuilder receiving(HttpReceiver receiver) {
				this.receiver = receiver;
				return this;
			}
			@Override
			public HttpReceiverRequestBuilder failing(Failing failing) {
				this.failing = failing;
				return this;
			}
			@Override
			public HttpReceiverRequestBuilder maxRedirections(int maxRedirections) {
				this.maxRedirections = maxRedirections;
				return this;
			}
			
			@Override
			public HttpReceiverRequest build() {
				final HttpReceiver r = receiver;
				final Failing f = failing;
				final int thisMaxRedirections = maxRedirections;
				
				return new HttpReceiverRequest() {
					private long id = -1L;
					private ReusableConnector reusableConnector = null;
					private HttpVersion requestVersion;
					private Multimap<String, String> completedHeaders;
					
					private void abruptlyClose(IOException ioe) {
						if (reusableConnector == null) {
							return;
						}
						
						reusableConnector.failNext(ioe);
						reusableConnector.connector.close();
						reusableConnectors.remove(id);
						reusableConnector = null;
					}
					
					private void prepare(HttpRequest request) {
						final HttpReceiver redirectingReceiver = new RedirectHttpReceiver(HttpClient.this, thisMaxRedirections, request, r, f);
						
						final Failing abruptlyClosingFailing = new Failing() {
							@Override
							public void failed(final IOException e) {
								LOGGER.trace("Error", e);

								executor.execute(new Runnable() {
									@Override
									public void run() {
										abruptlyClose(e);
									}
								});
								
								f.failed(e);
							}
						};
						
						final Disconnectable disconnectable = new Disconnectable() {
							@Override
							public void close() {
								executor.execute(new Runnable() {
									@Override
									public void run() {
										abruptlyClose(new IOException("Disconnected"));
									}
								});
							}
						};
						
						reusableConnector.push(new ClosingFailingReceiver() {
							private final LineReader lineReader = new LineReader();
							private boolean responseLineRead = false;
							private boolean responseHeadersRead;
				
							private boolean responseKeepAlive = false;
				
							private int responseCode;
							private String responseReason;
							private HttpVersion responseVersion;
							private final Multimap<String, String> responseHeaders = HashMultimap.create();
							
							private HttpContentReceiver responseReceiver;
							
							private boolean addHeader(String headerLine) {
								int i = headerLine.indexOf(HttpSpecification.HEADER_KEY_VALUE_SEPARATOR);
								if (i < 0) {
									abruptlyClosingFailing.failed(new IOException("Invalid header: " + headerLine));
									return false;
								}
								String key = headerLine.substring(0, i);
								String sanitizedKey = headerSanitization.get(key.toLowerCase());
								if (sanitizedKey != null) {
									key = sanitizedKey;
								}
								String value = headerLine.substring(i + 1).trim();
								responseHeaders.put(key, value);
								return true;
							}
							
							private boolean parseResponseLine(String responseLine) {
								int i = responseLine.indexOf(HttpSpecification.START_LINE_SEPARATOR);
								if (i < 0) {
									abruptlyClosingFailing.failed(new IOException("Invalid response: " + responseLine));
									return false;
								}
								int j = responseLine.indexOf(HttpSpecification.START_LINE_SEPARATOR, i + 1);
								if (j < 0) {
									abruptlyClosingFailing.failed(new IOException("Invalid response: " + responseLine));
									return false;
								}
								String version = responseLine.substring(0, i);
								if (!version.startsWith(HttpSpecification.HTTP_VERSION_PREFIX)) {
									abruptlyClosingFailing.failed(new IOException("Unsupported version: " + version));
									return false;
								}
								version = version.substring(HttpSpecification.HTTP_VERSION_PREFIX.length());
								if (version.equals(HttpVersion.HTTP10.toString())) {
									responseVersion = HttpVersion.HTTP10;
								} else if (version.equals(HttpVersion.HTTP11.toString())) {
									responseVersion = HttpVersion.HTTP11;
								} else {
									abruptlyClosingFailing.failed(new IOException("Unsupported version: " + version));
									return false;
								}
								String code = responseLine.substring(i + 1, j);
								try {
									responseCode = Integer.parseInt(code);
								} catch (NumberFormatException e) {
									abruptlyClosingFailing.failed(new IOException("Invalid status code: " + code));
									return false;
								}
								responseReason = responseLine.substring(j + 1);
								return true;
							}
							
							@Override
							public void closed() {
								executor.execute(new Runnable() {
									@Override
									public void run() {
										if (reusableConnector == null) {
											return;
										}
										
										if (responseReceiver != null) {
											responseReceiver.ended();
										}
				
										if (responseKeepAlive) {
											reusableConnector.pop();
										} else {
											abruptlyClose(new IOException("Connection not kept alive"));
										}
		
										reusableConnector = null;
									}
								});
							}
							
							@Override
							public void failed(IOException e) {
								f.failed(e);
							}
							
							@Override
							public void received(Connector c, final Address address, final ByteBuffer buffer) {
								executor.execute(new Runnable() {
									@Override
									public void run() {
										if (reusableConnector == null) {
											return;
										}
										
										while (!responseLineRead) {
											String line = lineReader.handle(buffer);
											if (line == null) {
												return;
											}
											LOGGER.trace("Response line: {}", line);
											if (!parseResponseLine(line)) {
												return;
											}
											responseLineRead = true;
											responseHeadersRead = false;
										}
										
										while (!responseHeadersRead) {
											String line = lineReader.handle(buffer);
											if (line == null) {
												return;
											}
											if (line.isEmpty()) {
												LOGGER.trace("Header line empty");
												responseHeadersRead = true;
												
												responseReceiver = redirectingReceiver.received(disconnectable, new HttpResponse(responseCode, responseReason, ImmutableMultimap.copyOf(responseHeaders)));

												for (String contentLengthValue : responseHeaders.get(HttpHeaderKey.CONTENT_LENGTH)) {
													try {
														long responseContentLength = Long.parseLong(contentLengthValue);
														responseReceiver = new ContentLengthReader(responseContentLength, abruptlyClosingFailing, responseReceiver);
													} catch (NumberFormatException e) {
														LOGGER.error("Invalid Content-Length: {}", contentLengthValue);
													}
													break;
												}
												
												for (String contentEncodingValue : responseHeaders.get(HttpHeaderKey.CONTENT_ENCODING)) {
													if (contentEncodingValue.equalsIgnoreCase(HttpHeaderValue.GZIP)) {
														responseReceiver = new GzipReader(abruptlyClosingFailing, responseReceiver);
													}
													break;
												}
												
												for (String transferEncodingValue : responseHeaders.get(HttpHeaderKey.TRANSFER_ENCODING)) {
													if (transferEncodingValue.equalsIgnoreCase(HttpHeaderValue.CHUNKED)) {
														responseReceiver = new ChunkedReader(abruptlyClosingFailing, responseReceiver);
													}
													break;
												}
								
												responseKeepAlive = (responseVersion != HttpVersion.HTTP10);
												for (String connectionValue : responseHeaders.get(HttpHeaderKey.CONNECTION)) {
													if (connectionValue.equalsIgnoreCase(HttpHeaderValue.CLOSE)) {
														responseKeepAlive = false;
													} else if (connectionValue.equalsIgnoreCase(HttpHeaderValue.KEEP_ALIVE)) {
														responseKeepAlive = true;
													}
												}
												LOGGER.trace("Keep alive = {}", responseKeepAlive);
											} else {
												LOGGER.trace("Header line: {}", line);
												if (!addHeader(line)) {
													return;
												}
											}
										}
										
										if (responseReceiver != null) {
											responseReceiver.received(buffer);
										}
									}
								});
							}
						});
						
						LOGGER.trace("Sending request: {}", request);
						
						reusableConnector.connector.send(null, LineReader.toBuffer(request.method.toString() + HttpSpecification.START_LINE_SEPARATOR + request.path + HttpSpecification.START_LINE_SEPARATOR + HttpSpecification.HTTP_VERSION_PREFIX + requestVersion.toString()));

						for (Map.Entry<String, String> h : completedHeaders.entries()) {
							String k = h.getKey();
							String v = h.getValue();
							reusableConnector.connector.send(null, LineReader.toBuffer(k + HttpSpecification.HEADER_KEY_VALUE_SEPARATOR + HttpSpecification.HEADER_BEFORE_VALUE + v));
						}
						
						reusableConnector.connector.send(null, emptyLineByteBuffer);
					}
					
					@Override
					public HttpContentSender create(final HttpRequest request) {
						requestVersion = HttpVersion.HTTP11;

						HttpContentSender sender = new HttpContentSender() {
							@Override
							public HttpContentSender send(final ByteBuffer buffer) {
								executor.execute(new Runnable() {
									@Override
									public void run() {
										if (reusableConnector == null) {
											return;
										}
										
										reusableConnector.connector.send(null, buffer);
									}
								});
								return this;
							}
							
							@Override
							public void finish() {
								executor.execute(new Runnable() {
									@Override
									public void run() {
										if (reusableConnector == null) {
											return;
										}
											
										reusableConnector.reusable = true;
									}
								});
							}
		
							@Override
							public void cancel() {
								executor.execute(new Runnable() {
									@Override
									public void run() {
										abruptlyClose(new IOException("Canceled"));
									}
								});
							}
						};
						
						completedHeaders = ArrayListMultimap.create(request.headers);
						if (!completedHeaders.containsKey(HttpHeaderKey.HOST)) {
							String portSuffix;
							if ((request.secure && (request.address.getPort() != HttpSpecification.DEFAULT_SECURE_PORT))
							|| (!request.secure && (request.address.getPort() != HttpSpecification.DEFAULT_PORT))) {
								portSuffix = String.valueOf(HttpSpecification.PORT_SEPARATOR) + String.valueOf(request.address.getPort());
							} else {
								portSuffix = "";
							}
							completedHeaders.put(HttpHeaderKey.HOST, request.address.getHost() + portSuffix);
						}
						if (!completedHeaders.containsKey(HttpHeaderKey.ACCEPT_ENCODING)) {
							completedHeaders.put(HttpHeaderKey.ACCEPT_ENCODING, HttpHeaderValue.GZIP);
						}
						if (!completedHeaders.containsKey(HttpHeaderKey.CONTENT_ENCODING)) {
							completedHeaders.put(HttpHeaderKey.CONTENT_ENCODING, HttpHeaderValue.GZIP);
						}
						if (!completedHeaders.containsKey(HttpHeaderKey.CONTENT_LENGTH) && !completedHeaders.containsKey(HttpHeaderKey.TRANSFER_ENCODING)) {
							completedHeaders.put(HttpHeaderKey.TRANSFER_ENCODING, HttpHeaderValue.CHUNKED);
						}
						if (!completedHeaders.containsKey(HttpHeaderKey.CONNECTION)) {
							completedHeaders.put(HttpHeaderKey.CONNECTION, HttpHeaderValue.KEEP_ALIVE);
						}
						if (!completedHeaders.containsKey(HttpHeaderKey.USER_AGENT)) {
							completedHeaders.put(HttpHeaderKey.USER_AGENT, DEFAULT_USER_AGENT);
						}
						if (!completedHeaders.containsKey(HttpHeaderKey.ACCEPT)) {
							completedHeaders.put(HttpHeaderKey.ACCEPT, DEFAULT_ACCEPT);
						}
						
						for (String transferEncodingValue : completedHeaders.get(HttpHeaderKey.TRANSFER_ENCODING)) {
							if (transferEncodingValue.equalsIgnoreCase(HttpHeaderValue.CHUNKED)) {
								LOGGER.debug("Request is chunked");
								sender = new ChunkedWriter(sender);
							}
							break;
						}
		
						for (String contentEncodingValue : completedHeaders.get(HttpHeaderKey.CONTENT_ENCODING)) {
							if (contentEncodingValue.equalsIgnoreCase(HttpHeaderValue.GZIP)) {
								LOGGER.debug("Request is gzip");
								sender = new GzipWriter(sender);
							}
							break;
						}
						
						for (String contentLengthValue : completedHeaders.get(HttpHeaderKey.CONTENT_LENGTH)) {
							try {
								long headerContentLength = Long.parseLong(contentLengthValue);
								LOGGER.debug("Request content length: {}", headerContentLength);
								sender = new ContentLengthWriter(headerContentLength, sender);
							} catch (NumberFormatException e) {
								LOGGER.error("Invalid Content-Length: {}", contentLengthValue);
							}
							break;
						}
						
						boolean headerKeepAlive = (requestVersion == HttpVersion.HTTP11);
						for (String connectionValue : completedHeaders.get(HttpHeaderKey.CONNECTION)) {
							if (connectionValue.equalsIgnoreCase(HttpHeaderValue.CLOSE)) {
								headerKeepAlive = false;
							} else if (connectionValue.equalsIgnoreCase(HttpHeaderValue.KEEP_ALIVE)) {
								headerKeepAlive = true;
							}
						}
						final boolean requestKeepAlive = headerKeepAlive;
						
						executor.execute(new Runnable() {
							@Override
							public void run() {
								if (id >= 0L) {
									throw new IllegalStateException("Could not be created twice");
								}
								
								if (requestKeepAlive) {
									for (Map.Entry<Long, ReusableConnector> e : reusableConnectors.entrySet()) {
										long reusedId = e.getKey();
										ReusableConnector reusedConnector = e.getValue();
										if ((pipelining && reusedConnector.reusable) || (!pipelining && (reusedConnector.receiver == null)) && (reusedConnector.secure == request.secure)) {
											id = reusedId;
			
											LOGGER.trace("Recycling connection {}", id);
											
											reusableConnector = reusedConnector;
	
											reusableConnector.reusable = false;
											prepare(request);
											return;
										}
									}
								}
		
								id = nextReusableConnectorId;
								nextReusableConnectorId++;
								
								reusableConnector = new ReusableConnector(request.secure ? secureConnectorFactory.to(request.address) : connectorFactory.to(request.address), queue, request.secure);
								reusableConnectors.put(id, reusableConnector);

								reusableConnector.reusable = false;
								prepare(request);
							}
						});
		
						return sender;
					}
				};
			}
		};
	}
}