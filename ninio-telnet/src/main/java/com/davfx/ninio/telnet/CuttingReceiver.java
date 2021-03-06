package com.davfx.ninio.telnet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import com.davfx.ninio.core.Address;
import com.davfx.ninio.core.Connection;

final class CuttingReceiver implements Connection {

	private final Connection wrappee;
	
	private final ByteBuffer prompt;
	private ByteBuffer currentPrompt;
	private List<ByteBuffer> previous = null;
	private final List<ByteBuffer> buffers = new LinkedList<>();
	private final int limit;
	private int count = 0;

	public CuttingReceiver(int limit, ByteBuffer prompt, Connection wrappee) {
		this.limit = limit;
		this.prompt = prompt;
		this.wrappee = wrappee;
		currentPrompt = prompt.duplicate();
	}
	public CuttingReceiver(int limit, Connection wrappee) {
		this.limit = limit;
		prompt = null;
		this.wrappee = wrappee;
		currentPrompt = null;
	}
	
	public void on(ByteBuffer prompt) {
		currentPrompt = prompt;
	}
	
	private static int find(ByteBuffer toFind, List<ByteBuffer> intoBuffers) {
		int total = 0;
		int lastLength = 0;
		for (ByteBuffer b : intoBuffers) {
			total += b.remaining();
			lastLength = b.remaining();
		}
		int totalExcludingLast = total - lastLength;
		total -= toFind.remaining() - 1;
		
		int intoBuffersIndex = 0;
		int backOffset = 0;
		for (int i = 0; i < total; i++) {
			while (true) {
				ByteBuffer first = intoBuffers.get(intoBuffersIndex);
				if ((i - backOffset) == first.remaining()) {
					intoBuffersIndex++;
					backOffset += first.remaining();
				} else {
					break;
				}
			}

			boolean diff = false;
			ByteBuffer intoBuffer = intoBuffers.get(intoBuffersIndex);
			int l = toFind.remaining();
			for (int j = 0; j < l; j++) {
				byte toFindByte = toFind.get(toFind.position() + j);
				
				byte intoBufferByte;
				while (true) {
					int k = i - backOffset + j;
					if (k == intoBuffer.remaining()) {
						intoBuffersIndex++;
						backOffset += intoBuffer.remaining();
						intoBuffer = intoBuffers.get(intoBuffersIndex);
					} else {
						intoBufferByte = intoBuffer.get(intoBuffer.position() + k);
						break;
					}
				}

				if (toFindByte != intoBufferByte) {
					diff = true;
					break;
				}
			}
			
			if (!diff) {
				return i + l - totalExcludingLast; // Position in last buffer
			}
		}
		
		return -1;
	}
	
	/*%%
	private static void debug(List<ByteBuffer> buffers) {
		StringBuilder b = new StringBuilder();
		for (ByteBuffer bb : buffers) {
			b.append(new String(bb.array(), bb.position(), bb.remaining(), TelnetSpecification.CHARSET));
		}
		LOGGER.debug("Checking: {}", b.toString());
	}
	*/
	
	@Override
	public void closed() {
		wrappee.closed();
	}
	@Override
	public void connected(Address address) {
		wrappee.connected(address);
	}
	@Override
	public void failed(IOException ioe) {
		wrappee.failed(ioe);
	}
	
	@Override
	public void received(Address address, ByteBuffer buffer) {
		//%% LOGGER.debug("Received: ***{}***", encode(ByteBufferUtils.toString(buffer)));
		while (true) {
			if (currentPrompt == null) {
				return;
			}
			
			if (previous == null) {
				previous = new ArrayList<>();
			}
			previous.add(buffer.duplicate());
			int position = find(currentPrompt, previous);
			
			int lengthToKeep = currentPrompt.remaining() - 1;
			int index = previous.size() - 1;
			List<ByteBuffer> newPrevious = new ArrayList<>();
			while ((lengthToKeep > 0) && (index >= 0)) {
				ByteBuffer b = previous.get(index);
				if (b.remaining() < lengthToKeep) {
					newPrevious.add(0, b);
					lengthToKeep -= b.remaining();
				} else {
					newPrevious.add(0, ByteBuffer.wrap(b.array(), b.arrayOffset() + b.position() + b.remaining() - lengthToKeep, lengthToKeep));
					break;
				}
				
				index--;
			}
			previous = newPrevious;

			if (position < 0) {
				buffers.add(buffer);
				count += buffer.remaining();
				if ((limit > 0) && (count >= limit)) {
					for (ByteBuffer b : buffers) {
						wrappee.received(address, b);
					}
		
					buffers.clear();
					count = 0;
					previous = null;
				}
				return;
			}
			
			ByteBuffer startBuffer = ByteBuffer.wrap(buffer.array(), buffer.position(), position);
			buffer = ByteBuffer.wrap(buffer.array(), buffer.arrayOffset() + buffer.position() + position, buffer.remaining() - position);
			//%% LOGGER.debug("Cut with prompt: ***{}*** --> /{}/ <> /{}/", encode(ByteBufferUtils.toString(currentPrompt)), encode(ByteBufferUtils.toString(startBuffer)), encode(ByteBufferUtils.toString(buffer)));
			buffers.add(startBuffer);

			for (ByteBuffer b : buffers) {
				wrappee.received(address, b);
			}
			wrappee.received(address, null);

			buffers.clear();
			count = 0;
			previous = null;
			if (prompt == null) {
				currentPrompt = null;
			} else {
				currentPrompt = prompt.duplicate();
			}
		}
	}

	/*%%
	private static String encode(String input) {
		try {
			return URLEncoder.encode(input, Charsets.UTF_8.name());
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}
	*/
}
