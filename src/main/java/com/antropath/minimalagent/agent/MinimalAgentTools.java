package com.antropath.minimalagent.agent;

import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class MinimalAgentTools {

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
        String url = "https://duckduckgo.com/html/?q=" + encode(query);
        try {
            Document document = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(20_000)
                    .get();

            Elements results = document.select(".result");
            List<String> lines = new ArrayList<>();
            for (Element result : results) {
                Element title = result.selectFirst(".result__title a");
                Element snippet = result.selectFirst(".result__snippet");
                if (title == null) {
                    continue;
                }
                String link = title.absUrl("href");
                String text = snippet != null ? snippet.text() : "";
                lines.add("[" + title.text() + "](" + link + ")\n" + text);
                if (lines.size() >= maxSearchResults) {
                    break;
                }
            }

            if (lines.isEmpty()) {
                return "No results found.";
            }
            return String.join("\n\n", lines);
        } catch (IOException e) {
            return "Search failed: " + e.getMessage();
        }
    }

    @Tool("Visit a webpage and return its readable text content as markdown-like plain text.")
    public String visitWebpage(String url) {
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

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
