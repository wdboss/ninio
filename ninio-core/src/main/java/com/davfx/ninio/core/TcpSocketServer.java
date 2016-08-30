package com.davfx.ninio.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.ninio.util.ConfigUtils;
import com.typesafe.config.Config;

public final class TcpSocketServer implements Listener {
	private static final Logger LOGGER = LoggerFactory.getLogger(TcpSocketServer.class);

	private static final Config CONFIG = ConfigUtils.load(new com.davfx.ninio.core.dependencies.Dependencies(), TcpSocketServer.class);
	private static final long WRITE_MAX_BUFFER_SIZE = CONFIG.getBytes("socket.write.buffer").longValue();
	// private static final double TIMEOUT = ConfigUtils.getDuration(CONFIG, "socket.timeout");

	public static interface Builder extends NinioBuilder<Listener> {
		Builder with(ByteBufferAllocator byteBufferAllocator);
		Builder bind(Address bindAddress);
	}

	public static Builder builder() {
		return new Builder() {
			private ByteBufferAllocator byteBufferAllocator = new DefaultByteBufferAllocator();
			
			private Address bindAddress = null;
			
			@Override
			public Builder bind(Address bindAddress) {
				this.bindAddress = bindAddress;
				return this;
			}

			@Override
			public Builder with(ByteBufferAllocator byteBufferAllocator) {
				this.byteBufferAllocator = byteBufferAllocator;
				return this;
			}

			@Override
			public TcpSocketServer create(Queue queue) {
				if (bindAddress == null) {
					throw new NullPointerException("bindAddress");
				}
				
				return new TcpSocketServer(queue, byteBufferAllocator, bindAddress);
			}
		};
	}
	
	private static final class ToWrite {
		public final ByteBuffer buffer;
		public final SendCallback callback;
		public ToWrite(ByteBuffer buffer, SendCallback callback) {
			this.buffer = buffer;
			this.callback = callback;
		}
	}

	private final Set<InnerSocketContext> outboundChannels = new HashSet<>();
	
	private final Queue queue;
	private final ByteBufferAllocator byteBufferAllocator;
	private final Address bindAddress;
	
	private ServerSocketChannel currentServerChannel = null;
	private SelectionKey currentAcceptSelectionKey = null;
	
	private boolean closed = false;
	private Listening listenCallback = null;

	private TcpSocketServer(Queue queue, ByteBufferAllocator byteBufferAllocator, Address bindAddress) {
		this.queue = queue;
		this.byteBufferAllocator = byteBufferAllocator;
		this.bindAddress = bindAddress;
	}
	
	@Override
	public void listen(final Listening callback) {
		queue.execute(new Runnable() {
			@Override
			public void run() {
				try {
					if (closed) {
						throw new IOException("Closed");
					}
					if (currentServerChannel != null) {
						throw new IllegalStateException("listen() cannot be called twice");
					}

					final ServerSocketChannel serverChannel = ServerSocketChannel.open();
					currentServerChannel = serverChannel;
					try {
						serverChannel.configureBlocking(false);
						//%% serverChannel.socket().setReceiveBufferSize();
						
						LOGGER.debug("-> Server channel ready to accept on: {}", bindAddress);

						final SelectionKey acceptSelectionKey = queue.register(serverChannel);
						currentAcceptSelectionKey = acceptSelectionKey;
						
						acceptSelectionKey.attach(new SelectionKeyVisitor() {
							@Override
							public void visit(SelectionKey key) {
								if (closed) {
									disconnect(serverChannel, acceptSelectionKey, callback);
									return;
								}
								
								if (!key.isAcceptable()) {
									return;
								}
								
								ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
								try {
									LOGGER.debug("-> Accepting client on: {}", bindAddress);
									final SocketChannel outboundChannel = ssc.accept();

									final InnerSocketContext context = new InnerSocketContext(outboundChannels);
									context.currentChannel = outboundChannel;

									final Address clientAddress = new Address(outboundChannel.socket().getInetAddress().getAddress(), outboundChannel.socket().getPort());

									final Connection connection = callback.connecting(new Connected() {
										@Override
										public void close() {
											queue.execute(new Runnable() {
												@Override
												public void run() {
													context.disconnectAndRemove();
												}
											});
										}
										
										@Override
										public void send(final Address address, final ByteBuffer buffer, final SendCallback callback) {
											queue.execute(new Runnable() {
												@Override
												public void run() {
													if (context.closed) {
														callback.failed(new IOException("Closed"));
														return;
													}

													if (address != null) {
														LOGGER.warn("Ignored send address: {}", address);
													}
													
													if ((WRITE_MAX_BUFFER_SIZE > 0L) && (context.toWriteLength > WRITE_MAX_BUFFER_SIZE)) {
														LOGGER.warn("Dropping {} bytes that should have been sent to {}", buffer.remaining(), address);
														callback.failed(new IOException("Packet dropped"));
														return;
													}
													
													context.toWriteQueue.add(new ToWrite(buffer, callback));
													context.toWriteLength += buffer.remaining();
													LOGGER.trace("Write buffer: {} bytes (current size: {} bytes)", buffer.remaining(), context.toWriteLength);
													
													SocketChannel channel = context.currentChannel;
													SelectionKey selectionKey = context.currentSelectionKey;
													if (channel == null) {
														return;
													}
													if (selectionKey == null) {
														return;
													}
													if (!channel.isOpen()) {
														return;
													}
													if (!selectionKey.isValid()) {
														return;
													}
													selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
												}
											});
										}
									});

									queue.execute(new Runnable() {
										@Override
										public void run() {
											try {
												if (closed) {
													throw new IOException("Closed");
												}
												
												try {
													// outboundChannel.socket().setSoTimeout((int) (TIMEOUT * 1000d)); // Not working with NIO
													outboundChannel.configureBlocking(false);

													final SelectionKey selectionKey = queue.register(outboundChannel);
													context.currentSelectionKey = selectionKey;

													selectionKey.attach(new SelectionKeyVisitor() {
														@Override
														public void visit(SelectionKey key) {
															if (closed) {
																context.disconnectAndRemove();
																return;
															}
															
															if (!outboundChannel.isOpen()) {
																return;
															}
															if (key.isReadable()) {
																final ByteBuffer readBuffer = byteBufferAllocator.allocate();
																try {
																	if (outboundChannel.read(readBuffer) < 0) {
																		context.disconnectAndRemove();
																		return;
																	}
																} catch (IOException e) {
																	LOGGER.trace("Connection failed", e);
																	context.disconnectAndRemove();
																	return;
																}
																
																readBuffer.flip();
																connection.received(null, readBuffer);
															} else if (key.isWritable()) {
																while (true) {
																	ToWrite toWrite = context.toWriteQueue.peek();
																	if (toWrite == null) {
																		break;
																	}
																	long before = toWrite.buffer.remaining();
																	try {
																		outboundChannel.write(toWrite.buffer);
																		context.toWriteLength -= before - toWrite.buffer.remaining();
																	} catch (IOException e) {
																		LOGGER.trace("Connection failed", e);
																		context.disconnectAndRemove();
																		return;
																	}
																	
																	if (toWrite.buffer.hasRemaining()) {
																		return;
																	}
																	
																	toWrite.callback.sent();
																	context.toWriteQueue.remove();
																}
																if (!outboundChannel.isOpen()) {
																	return;
																}
																if (!selectionKey.isValid()) {
																	return;
																}
																selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
															}
														}
													});
								
													selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_READ);
													if (!context.toWriteQueue.isEmpty()) {
														selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
													}

												} catch (IOException e) {
													LOGGER.trace("Connection failed", e);
													context.disconnectAndRemove();
													throw e;
												}
									
											} catch (IOException e) {
												context.disconnectAndRemove();
												connection.failed(e);
												return;
											}

											context.connection = connection;
											connection.connected(clientAddress);
										}
									});
								} catch (IOException e) {
									disconnect(serverChannel, acceptSelectionKey, callback);
									LOGGER.error("Error while accepting on: {}", bindAddress, e);
								}
							}
						});

						try {
							InetSocketAddress a = new InetSocketAddress(InetAddress.getByAddress(bindAddress.ip), bindAddress.port);
							LOGGER.debug("-> Bound on: {}", a);
							serverChannel.socket().bind(a);
							acceptSelectionKey.interestOps(acceptSelectionKey.interestOps() | SelectionKey.OP_ACCEPT);
						} catch (IOException e) {
							disconnect(serverChannel, acceptSelectionKey, null);
							throw new IOException("Could not bind to: " + bindAddress, e);
						}

						listenCallback = callback;
					} catch (IOException e) {
						disconnect(serverChannel, null, null);
						LOGGER.error("Error while creating server socket on: {}", bindAddress, e);
						callback.failed(e);
						return;
					}
				} catch (IOException ee) {
					LOGGER.error("Error while creating server socket on: {}", bindAddress, ee);
					callback.failed(ee);
					return;
				}

				callback.connected(null);
			}
		});
	}
	
	@Override
	public void close() {
		queue.execute(new Runnable() {
			@Override
			public void run() {
				disconnect(currentServerChannel, currentAcceptSelectionKey, listenCallback);
			}
		});
	}
	
	private void disconnect(ServerSocketChannel serverChannel, SelectionKey acceptSelectionKey, Listening callback) {
		for (InnerSocketContext context : outboundChannels) {
			LOGGER.debug("Closing outbound channel");
			context.disconnect();
		}
		outboundChannels.clear();

		if (serverChannel != null) {
			try {
				serverChannel.close();
			} catch (IOException e) {
			}
			LOGGER.debug("Server channel closed, bindAddress = {}", bindAddress);
		}
		if (acceptSelectionKey != null) {
			acceptSelectionKey.cancel();
		}
		
		currentServerChannel = null;
		currentAcceptSelectionKey = null;
		
		if (!closed) {
			closed = true;
	
			if (callback != null) {
				callback.closed();
			}
		}
	}

	private static final class InnerSocketContext {
		final Set<InnerSocketContext> outboundChannels;
		
		SocketChannel currentChannel = null;
		SelectionKey currentSelectionKey = null;
		Connection connection = null;

		final Deque<ToWrite> toWriteQueue = new LinkedList<>();
		long toWriteLength = 0L;
		
		boolean closed = false;
		
		public InnerSocketContext(Set<InnerSocketContext> outboundChannels) {
			this.outboundChannels = outboundChannels;

			outboundChannels.add(this);
			LOGGER.debug("-> Clients connected: {}", outboundChannels.size());
		}
		
		void disconnectAndRemove() {
			disconnect();
			
			outboundChannels.remove(this);
			LOGGER.debug("<- Clients connected: {}", outboundChannels.size());
		}
		
		void disconnect() {
			if (currentChannel != null) {
				try {
					currentChannel.socket().close();
				} catch (IOException e) {
				}
				try {
					currentChannel.close();
				} catch (IOException e) {
				}
			}
			if (currentSelectionKey != null) {
				currentSelectionKey.cancel();
			}

			IOException e = new IOException("Closed");
			for (ToWrite toWrite : toWriteQueue) {
				toWrite.callback.failed(e);
			}
			toWriteQueue.clear();

			currentChannel = null;
			currentSelectionKey = null;
			
			if (!closed) {
				closed = true;
				
				connection.closed();
			}
		}
	}
}