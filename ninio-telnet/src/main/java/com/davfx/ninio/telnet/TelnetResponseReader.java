package com.davfx.ninio.telnet;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.ninio.core.Address;
import com.davfx.ninio.core.CloseableByteBufferHandler;

final class TelnetResponseReader {
	private static final Logger LOGGER = LoggerFactory.getLogger(TelnetResponseReader.class);

	private static enum State {
		NONE,
		IAC,
		COMMAND,
		SUBCOMMAND,
		SUBCOMMAND_IAC,
	}

	public static final byte DONT = (byte) 254;
	public static final byte DO = (byte) 253;
	public static final byte WONT = (byte) 252;
	public static final byte WILL = (byte) 251;
	public static final byte IAC = (byte) 255;
	public static final byte SB = (byte) 250;
	public static final byte SE = (byte) 240;
	public static final byte ECHO = (byte) 1;

	private TelnetResponseReader.State readingCommand = State.NONE;
	private int lastRequest;
	private final StringBuilder subcommandLine = new StringBuilder();

	// private byte subCommandCode;

	private boolean closed = false;

	public TelnetResponseReader() {
	}

	public static ByteBuffer write(byte response, byte command) {
		return ByteBuffer.wrap(new byte[] { IAC, response, command });
	}

	public void handle(Address address, ByteBuffer buffer, CloseableByteBufferHandler wrappee, CloseableByteBufferHandler back) {
		if (closed) {
			return;
		}
		
		StringBuilder r = new StringBuilder();
		while (buffer.hasRemaining()) {
			byte b = buffer.get();
			switch (readingCommand) {
				case SUBCOMMAND:
					if (b == IAC) {
						readingCommand = State.SUBCOMMAND_IAC;
					} else {
						subcommandLine.append((char) b);
					}
					break;
				case SUBCOMMAND_IAC:
					if (b == IAC) {
						subcommandLine.append((char) b);
						readingCommand = State.SUBCOMMAND;
					} else if (b == SE) {
						subcommandLine.setLength(0);
						readingCommand = State.NONE;
					} else {
						LOGGER.error("Invalid telnet communication, missing SE");
						closed = true;
						back.close();
						return;
					}
					break;
				case NONE:
					if (b == IAC) {
						readingCommand = State.IAC;
					} else {
						r.append((char) b);
					}
					break;
				case IAC:
					if (b == IAC) {
						r.append((char) b);
						readingCommand = State.NONE;
					} else {
						lastRequest = b;
						readingCommand = State.COMMAND;
					}
					break;
				case COMMAND:
					if (lastRequest == SB) {
						// subCommandCode = b;
						readingCommand = State.SUBCOMMAND;
					} else {
						if (lastRequest == DO) {
							back.handle(address, write(WONT, b));
						} else if (lastRequest == WILL) {
							if (b == ECHO) {
								back.handle(address, write(DO, b));
							} else {
								back.handle(address, write(DONT, b));
							}
							/*
							} else if (lastRequest == DONT) {
								write.handle(address, write(WONT, b));
							} else if (lastRequest == WONT) {
								write.handle(address, write(DONT, b));
							*/
						} else {
							// Ignored
						}
						readingCommand = State.NONE;
					}
					break;
			}
		}

		if (r.length() > 0) {
			String s = r.toString();
			// LOGGER.debug("Core received: /{}/", s);
			wrappee.handle(address, ByteBuffer.wrap(s.getBytes(TelnetSpecification.CHARSET)));
		}
	}
}