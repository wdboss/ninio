package com.davfx.ninio.telnet;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.davfx.ninio.core.Address;
import com.davfx.ninio.core.CloseableByteBufferHandler;
import com.davfx.ninio.core.Listen;
import com.davfx.ninio.core.Queue;
import com.davfx.ninio.core.QueueListen;
import com.davfx.ninio.core.SocketListen;
import com.davfx.ninio.core.SocketListening;

public final class TelnetServer {

	public TelnetServer(final Queue queue, Address address, final SocketListening socketListening) {
		Listen listen = new SocketListen(queue.getSelector(), queue.allocator());
		
		new QueueListen(queue, listen).listen(address, new SocketListening() {
			
			@Override
			public void listening(Listening listening) {
				socketListening.listening(listening);
			}
			
			@Override
			public void close() {
				socketListening.close();
			}
			
			@Override
			public void failed(IOException e) {
				socketListening.failed(e);
			}
			
			@Override
			public CloseableByteBufferHandler connected(Address address, final CloseableByteBufferHandler connection) {
				final TelnetResponseReader reader = new TelnetResponseReader();

				final CloseableByteBufferHandler listeningConnection = socketListening.connected(address, new CloseableByteBufferHandler() {
					@Override
					public void handle(Address address, ByteBuffer buffer) {
						connection.handle(address, buffer);
					}
					
					@Override
					public void close() {
						connection.close();
					}
				});
				
				return new CloseableByteBufferHandler() {
					@Override
					public void close() {
						listeningConnection.close();
					}
					@Override
					public void handle(Address address, ByteBuffer buffer) {
						reader.handle(address, buffer, listeningConnection, connection);
					}
				};
			}
		});
	}
}