package com.antropath.minimalagent.agent;

import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class MinimalAgentTools {

    private static final Logger log = LoggerFactory.getLogger(MinimalAgentTools.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final int maxSearchResults;
    private final int maxWebpageCharacters;

    public MinimalAgentTools(
            @Value("${agent.search.max-results:5}") int maxSearchResults,
            @Value("${agent.webpage.max-characters:8000}") int maxWebpageCharacters
    ) {
        this.maxSearchResults = maxSearchResults;
        this.maxWebpageCharacters = maxWebpageCharacters;
    }

    @Tool("Search the web and return a short list of relevant results with titles, URLs, and snippets.")
    public String webSearch(String query) {
        log.info("webSearch called with query={}", query);
        List<String> attempts = List.of(
                searchBing(query),
                searchDuckDuckGo(query),
                searchBaidu(query)
        );
        for (String result : attempts) {
            if (result != null && !result.isBlank() && !result.startsWith("Search failed:")) {
                return result;
            }
        }
        return attempts.get(0);
    }

    @Tool("Visit a webpage and return its readable text content as markdown-like plain text.")
    public String visitWebpage(String url) {
        log.info("visitWebpage called with url={}", url);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Document document = Jsoup.parse(response.body(), url);
            document.select("script,style,noscript,svg").remove();
            String text = document.text().replaceAll("\\s+", " ").trim();
            if (text.length() > maxWebpageCharacters) {
                return text.substring(0, maxWebpageCharacters);
            }
            return text;
        } catch (Exception e) {
            return "Webpage fetch failed: " + e.getMessage();
        }
    }

    private String searchBing(String query) {
        return search(
                "https://www.bing.com/search?q=" + encode(query),
                ".b_algo",
                ".b_algo h2 a",
                ".b_caption p",
                "Search failed: ",
                "Bing"
        );
    }

    private String searchDuckDuckGo(String query) {
        return search(
                "https://duckduckgo.com/html/?q=" + encode(query),
                ".result",
                ".result__title a",
                ".result__snippet",
                "Search failed: ",
                "DuckDuckGo"
        );
    }

    private String searchBaidu(String query) {
        return search(
                "https://www.baidu.com/s?wd=" + encode(query),
                ".result.c-container",
                "h3 a",
                ".c-abstract",
                "Search failed: ",
                "Baidu"
        );
    }

    private String search(String url,
                          String resultSelector,
                          String titleSelector,
                          String snippetSelector,
                          String failurePrefix,
                          String sourceName) {
        try {
            Document document = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(15_000)
                    .get();

            Elements results = document.select(resultSelector);
            List<String> lines = new ArrayList<>();
            for (Element result : results) {
                Element title = result.selectFirst(titleSelector);
                if (title == null) {
                    continue;
                }
                Element snippet = result.selectFirst(snippetSelector);
                String link = title.absUrl("href");
                if (link == null || link.isBlank()) {
                    link = title.attr("href");
                }
                String text = snippet != null ? snippet.text() : "";
                if (text.isBlank()) {
                    text = title.text();
                }
                lines.add("[" + title.text() + "](" + link + ")\n" + text);
                if (lines.size() >= maxSearchResults) {
                    break;
                }
            }

            if (lines.isEmpty()) {
                return failurePrefix + sourceName + " returned no results.";
            }
            return String.join("\n\n", lines);
        } catch (IOException e) {
            return failurePrefix + sourceName + " - " + e.getMessage();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
