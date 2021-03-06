package com.davfx.ninio.http.service.controllers;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.ninio.core.InMemoryBuffers;
import com.davfx.ninio.http.HttpConnecter;
import com.davfx.ninio.http.HttpContentReceiver;
import com.davfx.ninio.http.HttpContentSender;
import com.davfx.ninio.http.HttpMethod;
import com.davfx.ninio.http.HttpReceiver;
import com.davfx.ninio.http.HttpRequest;
import com.davfx.ninio.http.HttpRequestAddress;
import com.davfx.ninio.http.HttpRequestBuilder;
import com.davfx.ninio.http.HttpResponse;
import com.davfx.ninio.http.HttpStatus;
import com.davfx.ninio.http.UrlUtils;
import com.davfx.ninio.http.service.HttpController;
import com.davfx.ninio.http.service.annotations.DefaultValue;
import com.davfx.ninio.http.service.annotations.Path;
import com.davfx.ninio.http.service.annotations.QueryParameter;
import com.davfx.ninio.http.service.annotations.Route;

// It's one of the default services
@Path("/services.image.convert")
public final class ImageToJpegConverter implements HttpController {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(ImageToJpegConverter.class);
	
	private final HttpConnecter client;
	
	public ImageToJpegConverter(HttpConnecter client) {
		this.client = client;
	}
	
	@Route(method = HttpMethod.GET)
	public Http convert(final @QueryParameter("url") String url, final @QueryParameter("quality") float quality, final @QueryParameter("width") @DefaultValue("0") int width, final @QueryParameter("height") @DefaultValue("0") int height) {
		LOGGER.debug("Converting: {}", url);

		return Http.ok().async(new HttpAsync() {
			@Override
			public void produce(final HttpAsyncOutput output) {
				UrlUtils.ParsedUrl parsedUrl = UrlUtils.parse(url);
				HttpRequestBuilder b = client.request();
				HttpContentSender sender = b.build(new HttpRequest(new HttpRequestAddress(parsedUrl.host, parsedUrl.port, parsedUrl.secure), HttpMethod.GET, parsedUrl.path, parsedUrl.headers));
				b.receive(new HttpReceiver() {
					@Override
					public void failed(IOException ioe) {
					}
					@Override
					public HttpContentReceiver received(final HttpResponse response) {
						if (response.status != HttpStatus.OK) {
							output.notFound().finish();
							return null;
						}
						return new HttpContentReceiver() {
							private final InMemoryBuffers buffers = new InMemoryBuffers();
							@Override
							public void received(ByteBuffer buffer) {
								buffers.add(buffer);
							}
							
							@Override
							public void ended() {
								byte[] unconverted = buffers.toByteArray();
								
								byte[] converted;
								try {
									BufferedImage image;
									try (InputStream in = new ByteArrayInputStream(unconverted)) {
										image = ImageIO.read(in);
									}
									
									if ((width > 0) || (height > 0)) {
										int w = width;
										int h = height;
										if (w <= 0) {
											w = image.getWidth() * h / image.getHeight();
										} else if (h <= 0) {
											h = image.getHeight() * w / image.getWidth();
										}
										
										LOGGER.debug("{}x{} -> {}x{}", image.getWidth(), image.getHeight(), w, h);
										
										BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
										Graphics2D g = scaled.createGraphics();
										try {
										    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
										    g.drawImage(image, 0, 0, w, h, 0, 0, image.getWidth(), image.getHeight(), null);
										} finally {
											g.dispose();
										}
										
										image = scaled;
									}
									
									ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
									try {
										ImageWriteParam writerParameters = writer.getDefaultWriteParam();
										writerParameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
										writerParameters.setCompressionQuality(quality);

										ByteArrayOutputStream out = new ByteArrayOutputStream();
										try {
											writer.setOutput(ImageIO.createImageOutputStream(out));
											writer.write(null, new IIOImage(image, null, null), writerParameters);
										} finally {
											out.close();
										}
										converted = out.toByteArray();
									} finally {
										writer.dispose();
									}
								} catch (IOException ioe) {
									LOGGER.error("Could not convert image: {}", ioe);
									output.internalServerError().finish();
									return;
								}

								output.ok().contentLength(converted.length).contentType("image/jpg").produce(ByteBuffer.wrap(converted)).finish();
							}
						};
					}
				});
				sender.finish();
			}
		});
	}
}
