package com.davfx.ninio.core.v3;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Deque;
import java.util.LinkedList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.ninio.core.Address;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public final class TcpSocket implements Connector {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(TcpSocket.class);

	private static final Config CONFIG = ConfigFactory.load(TcpSocket.class.getClassLoader());
	private static final long WRITE_MAX_BUFFER_SIZE = CONFIG.getBytes("ninio.socket.write.buffer").longValue();
	// private static final double TIMEOUT = ConfigUtils.getDuration(CONFIG, "ninio.socket.timeout");

	public static interface Builder extends NinioBuilder<Connector> {
		Builder with(ByteBufferAllocator byteBufferAllocator);
		Builder to(Address connectAddress);

		Builder failing(Failing failing);
		Builder closing(Closing closing);
		Builder connecting(Connecting connecting);
		Builder receiving(Receiver receiver);
	}

	public static Builder builder() {
		return new Builder() {
			private ByteBufferAllocator byteBufferAllocator = new DefaultByteBufferAllocator();
			
			private Address connectAddress = null;
			
			private Connecting connecting = null;
			private Closing closing = null;
			private Failing failing = null;
			private Receiver receiver = null;
			
			@Override
			public Builder closing(Closing closing) {
				this.closing = closing;
				return this;
			}
		
			@Override
			public Builder connecting(Connecting connecting) {
				this.connecting = connecting;
				return this;
			}
			
			@Override
			public Builder failing(Failing failing) {
				this.failing = failing;
				return this;
			}
			
			@Override
			public Builder receiving(Receiver receiver) {
				this.receiver = receiver;
				return this;
			}
			
			@Override
			public Builder with(ByteBufferAllocator byteBufferAllocator) {
				this.byteBufferAllocator = byteBufferAllocator;
				return this;
			}

			@Override
			public Builder to(Address connectAddress) {
				this.connectAddress = connectAddress;
				return this;
			}
			
			@Override
			public Connector create(Queue queue) {
				return new TcpSocket(queue, byteBufferAllocator, connectAddress, connecting, closing, failing, receiver);
			}
		};
	}
	
	private final Queue queue;

	private SocketChannel currentChannel = null;
	private SelectionKey currentInboundKey = null;
	private SelectionKey currentSelectionKey = null;

	private final Deque<ByteBuffer> toWriteQueue = new LinkedList<>();
	private long toWriteLength = 0L;

	private TcpSocket(final Queue queue, final ByteBufferAllocator byteBufferAllocator, final Address connectAddress, final Connecting connecting, final Closing closing, final Failing failing, final Receiver receiver) {
		this.queue = queue;

		queue.execute(new Runnable() {
			@Override
			public void run() {
				try {
					final SocketChannel channel = SocketChannel.open();
					currentChannel = channel;
					try {
						// channel.socket().setSoTimeout((int) (TIMEOUT * 1000d)); // Not working with NIO
						channel.configureBlocking(false);
						final SelectionKey inboundKey = queue.register(channel);
						inboundKey.interestOps(inboundKey.interestOps() | SelectionKey.OP_CONNECT);
						currentInboundKey = inboundKey;
						inboundKey.attach(new SelectionKeyVisitor() {
							@Override
							public void visit(SelectionKey key) {
								if (!key.isConnectable()) {
									return;
								}
				
								try {
									channel.finishConnect();
									final SelectionKey selectionKey = queue.register(channel);
									currentSelectionKey = selectionKey;
		
									selectionKey.attach(new SelectionKeyVisitor() {
										@Override
										public void visit(SelectionKey key) {
											if (!channel.isOpen()) {
												return;
											}
											
											if (key.isReadable()) {
												final ByteBuffer readBuffer = byteBufferAllocator.allocate();
												try {
													if (channel.read(readBuffer) < 0) {
														disconnect(channel, inboundKey, selectionKey);
														currentChannel = null;
														currentInboundKey = null;
														currentSelectionKey = null;
														if (closing != null) {
															closing.closed();
														}
														return;
													}
												} catch (IOException e) {
													LOGGER.trace("Connection failed", e);
													disconnect(channel, inboundKey, selectionKey);
													currentChannel = null;
													currentInboundKey = null;
													currentSelectionKey = null;
													if (closing != null) {
														closing.closed();
													}
													return;
												}

												readBuffer.flip();
												if (receiver != null) {
													receiver.received(TcpSocket.this, null, readBuffer);
												}
											} else if (key.isWritable()) {
												while (true) {
													ByteBuffer b = toWriteQueue.peek();
													if (b == null) {
														break;
													}
													long before = b.remaining();
													try {
														LOGGER.trace("Actual write buffer: {} bytes", b.remaining());
														channel.write(b);
														toWriteLength -= before - b.remaining();
													} catch (IOException e) {
														LOGGER.trace("Connection failed", e);
														disconnect(channel, inboundKey, selectionKey);
														currentChannel = null;
														currentInboundKey = null;
														currentSelectionKey = null;
														if (closing != null) {
															closing.closed();
														}
														return;
													}
													
													if (b.hasRemaining()) {
														return;
													}
													
													toWriteQueue.remove();
												}
												if (!channel.isOpen()) {
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
									if (!toWriteQueue.isEmpty()) {
										selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
									}
		
									if (connecting != null) {
										connecting.connected(TcpSocket.this);
									}
									
								} catch (final IOException e) {
									LOGGER.trace("Connection failed", e);
									disconnect(channel, inboundKey, null);
									currentChannel = null;
									currentInboundKey = null;
									currentSelectionKey = null;
									if (failing != null) {
										failing.failed(e);
									}
								}
							}
						});
						
						try {
							InetSocketAddress a = new InetSocketAddress(connectAddress.getHost(), connectAddress.getPort()); // Note this call blocks to resolve host (DNS resolution) //TODO Test with unresolved
							if (a.isUnresolved()) {
								throw new IOException("Unresolved address: " + connectAddress.getHost() + ":" + connectAddress.getPort());
							}
							LOGGER.trace("Connecting to: {}", a);
							channel.connect(a);
						} catch (IOException e) {
							disconnect(channel, inboundKey, null);
							throw new IOException("Could not connect to: " + connectAddress, e);
						}
					} catch (IOException e) {
						disconnect(channel, null, null);
						throw e;
					}
		
				} catch (final IOException e) {
					if (failing != null) {
						failing.failed(e);
					}
					return;
				}
			}
		});
	}

	private void disconnect(SocketChannel channel, SelectionKey inboundKey, SelectionKey selectionKey) {
		try {
			channel.socket().close();
		} catch (IOException e) {
		}
		try {
			channel.close();
		} catch (IOException e) {
		}
		if (inboundKey != null) {
			inboundKey.cancel();
		}
		if (selectionKey != null) {
			selectionKey.cancel();
		}
	}

	@Override
	public void close() {
		queue.execute(new Runnable() {
			@Override
			public void run() {
				if (currentChannel != null) {
					disconnect(currentChannel, currentInboundKey, currentSelectionKey);
				}
				currentChannel = null;
				currentInboundKey = null;
				currentSelectionKey = null;
			}
		});
	}
	
	@Override
	public Connector send(final Address address, final ByteBuffer buffer) {
		queue.execute(new Runnable() {
			@Override
			public void run() {
				if (address != null) {
					LOGGER.warn("Ignored send address: {}", address);
				}
				
				if ((WRITE_MAX_BUFFER_SIZE > 0L) && (toWriteLength > WRITE_MAX_BUFFER_SIZE)) {
					LOGGER.warn("Dropping {} bytes that should have been sent to {}", buffer.remaining(), address);
					return;
				}
				
				toWriteQueue.add(buffer);
				toWriteLength += buffer.remaining();
				LOGGER.trace("Write buffer: {} bytes (current size: {} bytes)", buffer.remaining(), toWriteLength);
				
				SocketChannel channel = currentChannel;
				SelectionKey selectionKey = currentSelectionKey;
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
		
		return this;
	}
}
