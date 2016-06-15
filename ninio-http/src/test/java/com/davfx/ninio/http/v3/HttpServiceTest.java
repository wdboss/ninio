package com.davfx.ninio.http.v3;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.ninio.core.v3.Address;
import com.davfx.ninio.core.v3.Disconnectable;
import com.davfx.ninio.core.v3.ListenConnecting;
import com.davfx.ninio.core.v3.Ninio;
import com.davfx.ninio.core.v3.TcpSocketServer;
import com.davfx.ninio.http.v3.service.Annotated;
import com.davfx.ninio.http.v3.service.HttpController;
import com.davfx.ninio.http.v3.service.HttpService;
import com.davfx.ninio.http.v3.service.annotations.Assets;
import com.davfx.ninio.http.v3.service.annotations.Path;
import com.davfx.ninio.http.v3.service.annotations.QueryParameter;
import com.davfx.ninio.http.v3.service.annotations.Route;
import com.google.common.base.Charsets;

public class HttpServiceTest {

	static {
		System.setProperty("http.keepAlive", "false");
	}
	
	private static Disconnectable server(Ninio ninio, int port, Class<? extends HttpController> clazz) {
		final boolean[] connected = new boolean[] { false };
		Disconnectable tcp = ninio.create(TcpSocketServer.builder().bind(new Address(Address.ANY, port))
				.connecting(new ListenConnecting() {
					@Override
					public void connected(Disconnectable disconnectable) {
						synchronized (connected) {
							connected[0] = true;
							connected.notifyAll();
						}
					}
				}).listening(
						HttpListening.builder().with(Executors.newSingleThreadExecutor()).with(
								Annotated.builder(HttpService.builder()).register(clazz).build()
								).build()));
		synchronized (connected) {
			while (!connected[0]) {
				try {
					connected.wait();
				} catch (InterruptedException e) {
				}
			}
		}
		return tcp;
	}

	@Path("/get")
	@Assets(path = "", index = "index2.html")
	public static final class TestGetWithQueryParameterController implements HttpController {
		@Route(method = HttpMethod.GET, path = "/hello")
		public Http echo(@QueryParameter("message") String message) {
			return Http.ok().content("GET hello:" + message);
		}
	}
	@Test
	public void testGetWithQueryParameter() throws Exception {
		try (Ninio ninio = Ninio.create()) {
			Disconnectable server = server(ninio, 8080, TestGetWithQueryParameterController.class);
			try {
				HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:8080/get/hello?message=world").openConnection();
				System.out.println(c.getRequestProperties());
				StringBuilder b = new StringBuilder();
				try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), Charsets.UTF_8))) {
					while (true) {
						String line = r.readLine();
						if (line == null) {
							break;
						}
						b.append(line).append('\n');
					}
				}
				System.out.println(c.getHeaderFields());
				c.disconnect();
				Assertions.assertThat(b.toString()).isEqualTo("GET hello:world\n");
				Thread.sleep(1000);
			} finally {
				server.close();
			}
		}
	}
}
