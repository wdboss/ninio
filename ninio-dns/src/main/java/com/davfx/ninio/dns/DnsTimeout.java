package com.davfx.ninio.dns;

import java.io.IOException;
import java.net.ProtocolFamily;

import com.davfx.ninio.core.Timeout;

public final class DnsTimeout {
	private DnsTimeout() {
	}
	
	public static DnsConnecter wrap(final double timeout, final DnsConnecter wrappee) {
		final Timeout t = new Timeout();
		return new DnsConnecter() {
			@Override
			public void close() {
				t.close();
				wrappee.close();
			}
			
			@Override
			public void connect(DnsConnection callback) {
				wrappee.connect(callback);
			}
			
			@Override
			public DnsRequestBuilder request() {
				return wrap(t, timeout, wrappee.request());
			}
		};
	}
	
	public static DnsRequestBuilder wrap(final Timeout t, final double timeout, final DnsRequestBuilder wrappee) {
		return new DnsRequestBuilder() {
			@Override
			public DnsRequestBuilder resolve(String host, ProtocolFamily family) {
				wrappee.resolve(host, family);
				return this;
			}

			@Override
			public void cancel() {
				wrappee.cancel();
			}
			
			@Override
			public Cancelable receive(final DnsReceiver callback) {
				final Timeout.Manager m = t.set(timeout);
				wrappee.receive(new DnsReceiver() {
					@Override
					public void failed(IOException ioe) {
						m.cancel();
						callback.failed(ioe);
					}
					
					@Override
					public void received(byte[] ip) {
						m.cancel();
						callback.received(ip);
					}
				});

				m.run(new Runnable() {
					@Override
					public void run() {
						wrappee.cancel();
						callback.failed(new IOException("Timeout"));
					}
				});

				return new Cancelable() {
					@Override
					public void cancel() {
						m.cancel();
						wrappee.cancel();
					}
				};
			}
		};
	}
}
