/**
 * Proxy servlet for PizzaFactory update sites.
 * 
 * Copyright (C) 2014,2015 PizzaFactory Project.
 * All rights reserved.
 * 
 * License: EPL-1.0.
 */

package jp.pizzafactory.webapp.composite.p2;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.exception.MethodInvocationException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

@WebServlet(name = "CompositeSite4NexusUnzip", urlPatterns = { "/*" })
public class CompositeSite4NexusUnzip extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private final String base_url = "http://builder.monami-ya.com:8080/nexus/content/unzip/master.group.unzip";
	private final String[] outTypes = { "compositeArtifacts.xml",
			"compositeContent.xml" };

	class GetIndexTask implements Runnable {
		private final String subUrl;
		private final int typeIdx;
		private final CyclicBarrier parentBarrier;
		private final List<String> urlList;

		public GetIndexTask(int typeIdx, String subUrl, List<String> urlList,
				CyclicBarrier parentBarrier) {
			this.typeIdx = typeIdx;
			this.urlList = urlList;
			this.subUrl = subUrl;
			this.parentBarrier = parentBarrier;
		}

		@Override
		public void run() {
			try {
				if (subUrl.charAt(0) == '.' || subUrl.contains("-SNAPSHOT")) {
					parentBarrier.await();
					return;
				}
				Runnable mergeTask = new Runnable() {

					@Override
					public void run() {
						System.err.println("Finished:" + subUrl);
					}
				};
				Document subDocument = Jsoup.connect(subUrl).get();
				Elements elements = subDocument.select("a");
				ExecutorService pool = Executors.newFixedThreadPool(elements
						.size());
				final CyclicBarrier cyclicBarrier = new CyclicBarrier(
						elements.size() + 1, mergeTask);

				for (final Element element : elements) {
					String linkUrl = element.attr("href");
					FindJarTask task = new FindJarTask(typeIdx, linkUrl,
							cyclicBarrier);
					pool.execute(task);
				}
				try {
					cyclicBarrier.await();
				} catch (InterruptedException | BrokenBarrierException ex) {
					ex.printStackTrace();
				}
				parentBarrier.await();
			} catch (InterruptedException | BrokenBarrierException
					| IOException ex) {
				ex.printStackTrace();
			}
		}

		class FindJarTask implements Runnable {
			private final String[] pingTypes = { "artifacts.jar", "content.jar" };

			private final int typeIdx;
			private final String linkUrl;
			private final CyclicBarrier barrier;

			public FindJarTask(int typeIdx, String url, CyclicBarrier barrier) {
				this.typeIdx = typeIdx;
				this.linkUrl = url;
				this.barrier = barrier;
			}

			@Override
			public void run() {
				try {
					if (linkUrl.endsWith("zip-unzip/")) {
						HttpURLConnection conn = (HttpURLConnection) new URL(
								linkUrl + pingTypes[typeIdx]).openConnection();
						conn.setRequestMethod("HEAD"); //$NON-NLS-1$
						int responseCode = conn.getResponseCode();
						conn.disconnect();
						if (responseCode == 200) {
							urlList.add(linkUrl);
						}
					}
					barrier.await();
				} catch (InterruptedException | BrokenBarrierException
						| IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}

	@Override
	protected void doGet(HttpServletRequest request,
			final HttpServletResponse response) throws ServletException,
			IOException {
		Velocity.setProperty("file.resource.loader.path", request
				.getServletContext().getRealPath("/WEB-INF/"));
		String search_url = base_url + request.getPathInfo();
		final int typeIdx;

		if (search_url.endsWith(outTypes[0])) {
			typeIdx = 0;
		} else if (search_url.endsWith(outTypes[1])) {
			typeIdx = 1;
		} else {
			typeIdx = -1;
			response.setContentType("text/plain");
			response.getWriter().print("Not found.");
			response.setStatus(404);
			return;
		}

		search_url = search_url.substring(0, search_url.length()
				- outTypes[typeIdx].length());
		Document document;
		document = Jsoup.connect(search_url).get();
		Elements elements = document.select("a");
		final List<String> urlList = new ArrayList<String>();

		Runnable mergeTask = new Runnable() {

			@Override
			public void run() {
				System.err.println("Search finished.");
				Velocity.init();
				VelocityContext context = new VelocityContext();
				context.put("timestamp", new Date().getTime());
				context.put("urlList", urlList);
				Template template = Velocity.getTemplate(outTypes[typeIdx]);
				response.setContentType("text/xml");
				response.setCharacterEncoding("utf-8");
				try {
					template.merge(context, response.getWriter());
				} catch (ResourceNotFoundException | ParseErrorException
						| MethodInvocationException | IOException e) {
					response.setStatus(500);
					e.printStackTrace();
				}
			}
		};
		ExecutorService pool = Executors.newFixedThreadPool(elements.size());
		final CyclicBarrier cyclicBarrier = new CyclicBarrier(
				elements.size() + 1, mergeTask);
		for (final Element e : elements) {
			String subUrl = e.attr("href");
			GetIndexTask subTask = new GetIndexTask(typeIdx, subUrl, urlList,
					cyclicBarrier);
			pool.execute(subTask);
		}
		try {
			cyclicBarrier.await();
		} catch (InterruptedException | BrokenBarrierException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	}

}
