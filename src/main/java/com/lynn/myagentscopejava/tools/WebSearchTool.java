package com.lynn.myagentscopejava.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lynn.myagentscopejava.core.tool.Tool;
import com.lynn.myagentscopejava.core.tool.ToolParam;
import com.lynn.myagentscopejava.core.tool.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 基于 Tavily 的网页搜索 + 内容抽取工具，供 agent 做 deep research。
 *
 * <p>Tavily 是专为 AI agent 设计的搜索 API：
 * <ul>
 *   <li>免注册门槛：tavily.com 注册即得 free tier（1000 次/月）</li>
 *   <li>返回清洗后的纯文本而不是原始 HTML</li>
 *   <li>响应格式简单稳定</li>
 * </ul>
 *
 * <p>工具会在 model 每次 reasoning 时按需被调用 —— 一次"deep research"通常会触发：
 * search 找候选 → fetch 看详情 → 必要时再 search 补充 → 综合回答。
 * 这是天然的 ReAct 循环，框架已经支持。
 */
public class WebSearchTool implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String SEARCH_URL = "https://api.tavily.com/search";
    private static final String EXTRACT_URL = "https://api.tavily.com/extract";
    /** 单个 fetch 结果最长截到这么多字符，避免一次塞爆上下文。 */
    private static final int MAX_FETCH_CHARS = 12000;

    private final WebClient webClient;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebSearchTool(WebClient webClient, String apiKey) {
        if (webClient == null) throw new IllegalArgumentException("webClient 必填");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Tavily apiKey 必填，去 tavily.com 申请免费 key");
        }
        this.webClient = webClient;
        this.apiKey = apiKey;
    }

    @Tool(name = "web_search",
            description = "用关键词在互联网上搜索最新信息。当用户问到的内容是实时事件、新闻、" +
                    "你训练数据之后才出现的事实、或任何你不确定答案的真实世界问题时，必须调用本工具。" +
                    "返回若干条搜索结果（含标题、URL、摘要）。")
    public String webSearch(
            @ToolParam(description = "搜索关键词，使用自然语言或精炼短语，中英文都可以") String query,
            @ToolParam(description = "返回结果数量，建议 3-8，默认 5", required = false) int maxResults) {
        if (maxResults <= 0) maxResults = 5;
        if (maxResults > 20) maxResults = 20;

        ObjectNode body = mapper.createObjectNode();
        body.put("api_key", apiKey);
        body.put("query", query);
        body.put("search_depth", "advanced");  // 'basic' or 'advanced'，advanced 质量更高
        body.put("include_answer", false);
        body.put("include_raw_content", false);
        body.put("max_results", maxResults);

        try {
            String resp = postJson(SEARCH_URL, body);
            return formatSearchResults(resp);
        } catch (Exception e) {
            log.warn("web_search 调用失败：{}", e.toString());
            return "[search error] " + e.getMessage();
        }
    }

    @Tool(name = "web_fetch",
            description = "拉取指定 URL 的网页正文（已抽取为干净文本，不是 HTML）。" +
                    "在 web_search 之后，对感兴趣的某条结果想看完整内容时调用。" +
                    "返回的内容会被截断到大约 12000 字符以保护上下文。")
    public String webFetch(
            @ToolParam(description = "完整的网页 URL，必须以 http:// 或 https:// 开头") String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return "[fetch error] 不是合法的 URL：" + url;
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("api_key", apiKey);
        ArrayNode urls = body.putArray("urls");
        urls.add(url);

        try {
            String resp = postJson(EXTRACT_URL, body);
            return formatExtractResult(resp, url);
        } catch (Exception e) {
            log.warn("web_fetch 调用失败 url={}: {}", url, e.toString());
            return "[fetch error] " + e.getMessage();
        }
    }

    // ------------- 内部 -------------

    private String postJson(String url, ObjectNode body) throws Exception {
        return webClient.post()
                .uri(url)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(mapper.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private String formatSearchResults(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return "[no results]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(results.size()).append(" 条结果：\n\n");
        int i = 1;
        for (JsonNode r : results) {
            sb.append("[").append(i++).append("] ").append(r.path("title").asText("(无标题)")).append('\n');
            sb.append("URL: ").append(r.path("url").asText("")).append('\n');
            String content = r.path("content").asText("");
            if (!content.isEmpty()) {
                if (content.length() > 600) content = content.substring(0, 600) + "...";
                sb.append("摘要: ").append(content).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String formatExtractResult(String json, String url) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            JsonNode failed = root.path("failed_results");
            if (failed.isArray() && !failed.isEmpty()) {
                return "[fetch failed] " + failed.get(0).path("error").asText("unknown");
            }
            return "[no content extracted from " + url + "]";
        }
        JsonNode first = results.get(0);
        String content = first.path("raw_content").asText("");
        if (content.isEmpty()) return "[empty content from " + url + "]";
        if (content.length() > MAX_FETCH_CHARS) {
            content = content.substring(0, MAX_FETCH_CHARS) +
                    "\n\n[...内容已截断，原文共 " + content.length() + " 字符]";
        }
        return content;
    }
}
