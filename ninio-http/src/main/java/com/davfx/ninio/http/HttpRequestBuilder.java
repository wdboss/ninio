package com.davfx.ninio.http;

import com.davfx.ninio.core.Failing;

public interface HttpRequestBuilder {
	HttpRequestBuilder failing(Failing failing);
	HttpRequestBuilder receiving(HttpReceiver receiver);
	HttpRequestBuilder maxRedirections(int maxRedirections);
	HttpContentSender build(HttpRequest request);
}