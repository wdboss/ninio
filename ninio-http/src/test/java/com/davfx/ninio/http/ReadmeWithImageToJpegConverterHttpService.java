package com.davfx.ninio.http;

import com.davfx.ninio.core.Address;
import com.davfx.ninio.core.Listener;
import com.davfx.ninio.core.Ninio;
import com.davfx.ninio.core.TcpSocketServer;
import com.davfx.ninio.dns.DnsClient;
import com.davfx.ninio.dns.DnsConnecter;
import com.davfx.ninio.http.service.Annotated;
import com.davfx.ninio.http.service.HttpService;
import com.davfx.ninio.http.service.controllers.ImageToJpegConverter;

public final class ReadmeWithImageToJpegConverterHttpService {

	public static void main(String[] args) throws Exception {
		int port = 8080;
		try (Ninio ninio = Ninio.create()) {
			try (DnsConnecter dns = ninio.create(DnsClient.builder()); HttpConnecter client = ninio.create(HttpClient.builder())) {
				Annotated.Builder a = Annotated.builder(HttpService.builder());
				a.register(null, new ImageToJpegConverter(client));
		
				try (Listener tcp = ninio.create(TcpSocketServer.builder().bind(new Address(Address.ANY, port)))) {
					tcp.listen(ninio.create(HttpListening.builder().with(a.build())));
		
					String url = "http://www.journaldugeek.com/wp-content/blogs.dir/1/files/2015/10/delorean-back-to-the-future.jpg";
					System.out.println("http://" + new Address(Address.LOCALHOST, port) + "/services.image.convert?url=" + UrlUtils.encode(url) + "&quality=1&width=100&height=100");
	
					Thread.sleep(60000);
				}
			}
		}
	}

}
