package com.davfx.ninio.core;

import java.io.IOException;

import com.davfx.ninio.util.Wait;

public final class WaitConnectedListenerCallback implements Listener.Callback {
	private final Wait wait;
	private final Listener.Callback wrappee;
	
	public WaitConnectedListenerCallback(Wait wait, Listener.Callback wrappee) {
		this.wait = wait;
		this.wrappee = wrappee;
	}
	
	@Override
	public void connected() {
		wait.run();
		wrappee.connected();
	}
	
	@Override
	public void closed() {
		wrappee.closed();
	}
	@Override
	public void failed(IOException ioe) {
		wrappee.failed(ioe);
	}
	@Override
	public Connecting connecting() {
		return wrappee.connecting();
	}
}