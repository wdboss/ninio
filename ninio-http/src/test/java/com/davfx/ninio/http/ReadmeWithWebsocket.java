package com.davfx.ninio.http;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.ninio.core.Address;
import com.davfx.ninio.core.CloseableByteBufferHandler;
import com.davfx.ninio.core.FailableCloseableByteBufferHandler;
import com.davfx.ninio.core.Queue;
import com.davfx.ninio.core.ReadyConnection;
import com.davfx.ninio.http.websocket.WebsocketHttpServerHandler;
import com.davfx.ninio.util.QueueScheduled;
import com.davfx.util.Wait;
import com.google.common.base.Charsets;

public final class ReadmeWithWebsocket {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(ReadmeWithWebsocket.class);
	
	public static void main(String[] args) {
		Wait wait = new Wait();
		int port = 8080;
		try (Queue queue = new Queue(); HttpServer server = new HttpServer(queue, null, new Address(Address.ANY, port), new HttpServerHandlerFactory() {
			@Override
			public void failed(IOException e) {
			}
			@Override
			public void closed() {
			}
			
			@Override
			public HttpServerHandler create() {
				return new DispatchHttpServerHandler(
					new HttpRequestFunctionContainer()
						.add(new SubPathHttpRequestFilter(HttpQueryPath.of("/ws")), new WebsocketHttpServerHandler(false, new ReadyConnection() {
							private CloseableByteBufferHandler write;
							@Override
							public void handle(Address address, ByteBuffer buffer) {
								String s = new String(buffer.array(), buffer.position(), buffer.remaining(), Charsets.UTF_8);
								LOGGER.debug("Received {} <--: {}", address, s);
								echo(s);
							}
							
							private void echo(final String s) {
								write.handle(null, ByteBuffer.wrap(("echo " + s).getBytes(Charsets.UTF_8)));
								QueueScheduled.run(queue, 1d, new Runnable() {
									@Override
									public void run() {
										echo(s);
									}
								});
							}
							
							@Override
							public void connected(FailableCloseableByteBufferHandler write) {
								LOGGER.debug("Connected <--");
								this.write = write;
							}
							
							@Override
							public void close() {
								LOGGER.debug("Closed <--");
							}
							
							@Override
							public void failed(IOException e) {
								LOGGER.warn("Failed <--", e);
							}
						}))
						.add(new SubPathHttpRequestFilter(HttpQueryPath.of()), new FileHttpServerHandler(new File("src/test/resources"), HttpQueryPath.of()))
					);
				}
		})) {
			System.out.println("http://" + new Address(Address.LOCALHOST, port) + "/ws.html");
			wait.waitFor();
		}
	}
}
