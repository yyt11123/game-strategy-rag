package com.game.rag.web;

import com.game.rag.rag.RagAnswer;
import com.game.rag.rag.RagService;
import com.game.rag.rag.SearchResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简易 Web 服务：提供一个极简的网页界面用于问答演示。
 *
 * 使用 JDK 内置的 HttpServer（com.sun.net.httpserver），
 * 不需要 Spring Boot 或任何第三方 Web 框架，保持依赖精简。
 *
 * 访问方式：启动后打开浏览器访问 http://localhost:8080
 *
 * 页面功能：
 * - 输入框：输入问题
 * - 回答区：显示 AI 生成的回答
 * - 来源区：显示答案引用的攻略文件和片段
 */
public class WebServer {

    /** 监听端口 */
    private static final int PORT = 8080;

    /** RAG 服务引用 */
    private final RagService ragService;

    /** HTTP 服务器实例 */
    private final HttpServer server;

    /** JSON 序列化/反序列化 */
    private static final Gson gson = new Gson();

    public WebServer(RagService ragService) throws IOException {
        this.ragService = ragService;

        this.server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // 注册路由
        server.createContext("/", this::handleIndex);          // 首页
        server.createContext("/api/ask", this::handleAsk);     // 问答 API
        server.createContext("/api/upload", this::handleUpload); // 上传文件 API
        server.createContext("/static/", this::handleStatic);  // 静态资源

        server.setExecutor(null); // 使用默认的线程池
    }

    /**
     * 启动 Web 服务。
     */
    public void start() {
        server.start();
        System.out.println("🌐 Web 服务已启动：http://localhost:" + PORT);
        System.out.println("   在浏览器中打开上述地址即可使用。");
        System.out.println("   按 Ctrl+C 或在控制台输入 /quit 退出。\n");
    }

    /**
     * 停止 Web 服务。
     */
    public void stop() {
        server.stop(0);
        System.out.println("🌐 Web 服务已停止。");
    }

    // ==================== 路由处理 ====================

    /**
     * 首页：返回 HTML 页面。
     */
    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String html = buildHtmlPage();
        sendResponse(exchange, 200, "text/html; charset=utf-8", html);
    }

    /**
     * 问答 API：POST /api/ask，接收 JSON，返回 JSON。
     * 请求体：question=用户问题
     * 响应体：JSON 格式的 { answer, sources }
     */
    private void handleAsk(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, Map.of("error", "只支持POST请求"));
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String question;

        // 尝试 JSON 解析；失败则回退 form-urlencoded
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            question = json.has("question") ? json.get("question").getAsString() : null;
        } catch (JsonSyntaxException e) {
            question = parseUrlEncodedField(body, "question");
        }

        if (question == null || question.isBlank()) {
            respondJson(exchange, 400, Map.of("error", "问题不能为空"));
            return;
        }

        try {
            RagAnswer answer = ragService.ask(question);
            respondJson(exchange, 200, answerToMap(answer));
        } catch (Exception e) {
            respondJson(exchange, 500, Map.of("error",
                    "处理请求时出错：" + e.getMessage()));
        }
    }

    /**
     * 上传文件 API：POST /api/upload，接收 JSON { fileName, content, type }.
     * txt 文件：content 为 UTF-8 文本；pdf 文件：content 为 base64 编码。
     * 调用 KnowledgeBase.addDocument() 进行切块 / 向量化 / 入库。
     */
    private void handleUpload(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondJson(exchange, 405, Map.of("error", "只支持POST请求"));
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String fileName = json.has("fileName") ? json.get("fileName").getAsString() : null;
            String content  = json.has("content")  ? json.get("content").getAsString()  : null;
            String type     = json.has("type")     ? json.get("type").getAsString()     : null;

            if (fileName == null || fileName.isBlank()
                    || content == null || content.isBlank()
                    || type == null || type.isBlank()) {
                respondJson(exchange, 400, Map.of("error", "缺少必要字段：fileName / content / type"));
                return;
            }

            if (!type.equalsIgnoreCase("txt") && !type.equalsIgnoreCase("pdf")) {
                respondJson(exchange, 400, Map.of("error", "仅支持 txt 和 pdf 文件类型"));
                return;
            }

            int count = ragService.addDocument(fileName, content, type);
            respondJson(exchange, 200, Map.of(
                    "ok", true,
                    "message", "文件已解析入库",
                    "chunks", (Object) count));
        } catch (JsonSyntaxException e) {
            respondJson(exchange, 400, Map.of("error", "JSON 格式错误：" + e.getMessage()));
        } catch (Exception e) {
            respondJson(exchange, 500, Map.of("error", "文件处理失败：" + e.getMessage()));
        }
    }

    /**
     * 静态资源路由：GET /static/* → 从 classpath:/static/ 读取文件。
     * 用于提供头像图片等静态资源。
     */
    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        // 提取 /static/ 后面的路径
        String path = exchange.getRequestURI().getPath();
        String fileName = path.substring("/static/".length());
        if (fileName.isBlank() || fileName.contains("..") || fileName.contains("\\")) {
            sendResponse(exchange, 404, "text/plain", "Not Found");
            return;
        }

        String resourcePath = "/static/" + fileName;
        InputStream is = getClass().getResourceAsStream(resourcePath);
        if (is == null) {
            sendResponse(exchange, 404, "text/plain", "Not Found");
            return;
        }

        byte[] bytes = is.readAllBytes();
        is.close();

        // 根据文件头 magic bytes 检测真实格式（兼容 .png 后缀但内容是 JPEG 的情况）
        String contentType = "application/octet-stream";
        if (bytes.length > 4) {
            int h = ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                  | ((bytes[2] & 0xFF) << 8)  |  (bytes[3] & 0xFF);
            if (h == 0x89504E47)       contentType = "image/png";
            else if (h == 0xFFD8FFE0 || h == 0xFFD8FFE1 || h == 0xFFD8FFE2
                  || (h & 0xFFFF0000) == 0xFFD80000) contentType = "image/jpeg";
            else if (h == 0x47494638)  contentType = "image/gif";
            else if (bytes[0] == '<' && bytes[1] == 's' && bytes[2] == 'v' && bytes[3] == 'g')
                contentType = "image/svg+xml";
        }

        sendResponse(exchange, 200, contentType, bytes);
    }

    // ==================== 辅助方法 ====================

    /**
     * 用 Gson 将 Map 序列化为 JSON 并发送响应。
     * Gson 自动转义换行、引号等特殊字符，杜绝手动拼接导致的 JSON 语法错误。
     */
    private static void respondJson(HttpExchange exchange, int statusCode, Map<String, Object> data)
            throws IOException {
        String json = gson.toJson(data);
        sendResponse(exchange, statusCode, "application/json; charset=utf-8", json);
    }

    /**
     * 将 RagAnswer 转为可序列化的 Map（供 Gson 输出）。
     */
    private static Map<String, Object> answerToMap(RagAnswer answer) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("answer", answer.answer());

        List<SearchResult> sources = answer.sources();
        List<Map<String, Object>> srcMaps = new ArrayList<>();
        for (SearchResult src : sources) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("fileName", src.fileName() != null ? src.fileName() : "未知");
            sm.put("score", src.score());
            String snippet = src.content();
            if (snippet.length() > 300) {
                snippet = snippet.substring(0, 300) + "...";
            }
            sm.put("snippet", snippet);
            srcMaps.add(sm);
        }
        map.put("sources", srcMaps);
        return map;
    }

    /**
     * 从 form-urlencoded 体中提取单个字段（回退方案）。
     */
    private static String parseUrlEncodedField(String body, String key) {
        String prefix = key + "=";
        if (body.startsWith(prefix)) {
            return URLDecoder.decode(body.substring(prefix.length()), StandardCharsets.UTF_8);
        }
        int idx = body.indexOf("&" + prefix);
        if (idx >= 0) {
            return URLDecoder.decode(body.substring(idx + 1 + prefix.length()), StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * 统一发送 HTTP 响应。
     */
    private static void sendResponse(HttpExchange exchange, int statusCode, String contentType, String body)
            throws IOException {
        sendResponse(exchange, statusCode, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String contentType, byte[] bytes)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 构建 HTML 页面。
     * 使用内嵌 CSS 和 JS，让整个 Web 界面在一个文件里，无需额外资源。
     */
    private String buildHtmlPage() {
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Mochi · 游戏攻略智能问答</title>
                <style>
                    :root {
                        --bg:         #1A1A1A;
                        --bg-card:    #262626;
                        --bg-overlay: rgba(0,0,0,0.78);
                        --text:       #ECECEC;
                        --text-muted: #9B9B9B;
                        --text-faint: #6E6E6E;
                        --accent:     #D97757;
                        --accent-hover:#E08D6E;
                        --border:     rgba(255,255,255,0.07);
                        --shadow:     0 1px 3px rgba(0,0,0,0.25);
                        --radius:     14px;
                        --radius-sm:  10px;
                        --max-w:      760px;
                    }

                    * { margin: 0; padding: 0; box-sizing: border-box; }

                    body {
                        font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
                        background: var(--bg);
                        color: var(--text);
                        min-height: 100vh;
                        line-height: 1.65;
                        -webkit-font-smoothing: antialiased;
                    }

                    /* ── Layout ── */
                    .page {
                        display: flex;
                        flex-direction: column;
                        min-height: 100vh;
                    }

                    .header {
                        text-align: center;
                        padding: 48px 20px 28px;
                    }

                    .header .logo {
                        font-size: 28px;
                        font-weight: 700;
                        color: var(--text);
                        letter-spacing: -0.3px;
                    }

                    .main {
                        flex: 1;
                        width: 100%;
                        max-width: var(--max-w);
                        margin: 0 auto;
                        padding: 0 20px 120px;
                    }

                    /* ── Welcome ── */
                    .welcome {
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        min-height: 50vh;
                        text-align: center;
                        padding: 40px 20px;
                    }
                    .welcome .greeting {
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        gap: 10px;
                    }
                    .welcome .greeting .star {
                        font-size: 18px;
                        color: var(--accent);
                        line-height: 1;
                        opacity: 0.85;
                    }
                    .welcome .greeting .text {
                        font-family: Georgia, "Times New Roman", "Noto Serif SC", serif;
                        font-size: 28px;
                        color: #D5CFC6;
                        letter-spacing: -0.2px;
                    }

                    /* ── File card (above messages, below input bar) ── */
                    .file-card-wrap {
                        position: fixed;
                        bottom: 80px;
                        left: 0; right: 0;
                        display: flex;
                        justify-content: center;
                        z-index: 99;
                        pointer-events: none;
                    }
                    .file-card {
                        display: flex;
                        align-items: center;
                        gap: 10px;
                        padding: 8px 14px;
                        background: var(--bg-card);
                        border: 1px solid var(--border);
                        border-radius: var(--radius-sm);
                        font-size: 13px;
                        color: var(--text-muted);
                        box-shadow: var(--shadow);
                        pointer-events: all;
                        max-width: var(--max-w);
                    }
                    .file-card .file-icon { font-size: 16px; }
                    .file-card .file-name {
                        font-weight: 500;
                        color: var(--text);
                        max-width: 260px;
                        overflow: hidden;
                        text-overflow: ellipsis;
                        white-space: nowrap;
                    }
                    .file-card .file-status { color: var(--text-faint); white-space: nowrap; }
                    .file-card .file-remove {
                        background: none; border: none;
                        color: var(--text-faint); cursor: pointer;
                        font-size: 16px; padding: 0 2px;
                        transition: color 0.15s;
                    }
                    .file-card .file-remove:hover { color: var(--accent); }

                    /* ── Drag-overlay ── */
                    .drag-overlay {
                        display: none;
                        position: fixed;
                        inset: 0;
                        background: var(--bg-overlay);
                        z-index: 200;
                        align-items: center;
                        justify-content: center;
                    }
                    .drag-overlay.active { display: flex; }
                    .drag-overlay .drop-zone {
                        padding: 48px 64px;
                        border: 2px dashed var(--accent);
                        border-radius: var(--radius);
                        text-align: center;
                        color: var(--text);
                        background: rgba(38,38,38,0.70);
                    }
                    .drag-overlay .drop-zone .drop-icon { font-size: 36px; margin-bottom: 12px; color: var(--accent); }
                    .drag-overlay .drop-zone .drop-text { font-size: 16px; }
                    .drag-overlay .drop-zone .drop-ext  { font-size: 12px; color: var(--text-faint); margin-top: 6px; }

                    /* ── Conversation bubbles ── */
                    .msg-row {
                        display: flex;
                        align-items: flex-start;
                        gap: 10px;
                        margin-bottom: 24px;
                    }
                    /* User: right-aligned */
                    .msg-row.user {
                        flex-direction: row-reverse;
                    }

                    .msg-avatar {
                        flex-shrink: 0;
                        width: 38px; height: 38px;
                        border-radius: 50%;
                        position: relative;
                        overflow: hidden;
                        background: var(--bg-card);
                        border: 1px solid var(--border);
                    }
                    .msg-avatar img {
                        width: 100%; height: 100%;
                        object-fit: cover;
                        display: block;
                        position: relative;
                        z-index: 1;
                    }
                    /* Fallback placeholder shown when img is removed (onerror) */
                    .msg-avatar .avatar-placeholder {
                        position: absolute;
                        inset: 0;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        font-size: 18px;
                        color: var(--text-muted);
                        z-index: 0;
                    }

                    .msg-bubble {
                        max-width: 82%;
                        padding: 12px 18px;
                        border-radius: 16px;
                        font-size: 15px;
                        line-height: 1.65;
                        word-break: break-word;
                    }
                    /* AI bubble: left, slightly lighter background */
                    .msg-row.ai .msg-bubble {
                        background: #242424;
                        border: 1px solid rgba(255,255,255,0.05);
                        color: var(--text);
                        border-top-left-radius: 4px;
                    }
                    /* User bubble: right, warm-tinted dark */
                    .msg-row.user .msg-bubble {
                        background: #2A231E;
                        border: 1px solid rgba(217,119,87,0.10);
                        color: var(--text);
                        border-top-right-radius: 4px;
                    }

                    /* AI bubble Markdown */
                    .msg-row.ai .msg-bubble h3 {
                        font-size: 17px; font-weight: 600; margin: 16px 0 8px; color: var(--text);
                    }
                    .msg-row.ai .msg-bubble h4 {
                        font-size: 15px; font-weight: 600; margin: 14px 0 6px; color: var(--text);
                    }
                    .msg-row.ai .msg-bubble p  { margin: 0 0 10px; }
                    .msg-row.ai .msg-bubble ul, .msg-row.ai .msg-bubble ol { margin: 4px 0 12px 20px; }
                    .msg-row.ai .msg-bubble li { margin-bottom: 4px; }
                    .msg-row.ai .msg-bubble strong { font-weight: 600; color: var(--text); }
                    .msg-row.ai .msg-bubble em { font-style: italic; color: var(--text-muted); }

                    /* ── Sources (inside AI bubble) ── */
                    .sources {
                        margin-top: 14px;
                    }
                    .sources summary {
                        cursor: pointer;
                        font-size: 12px;
                        color: var(--text-faint);
                        font-weight: 500;
                        letter-spacing: 0.3px;
                        list-style: none;
                        display: flex;
                        align-items: center;
                        gap: 4px;
                        user-select: none;
                    }
                    .sources summary::-webkit-details-marker { display: none; }
                    .sources summary::before {
                        content: '▸';
                        display: inline-block;
                        font-size: 10px;
                        margin-right: 4px;
                        transition: transform 0.15s;
                    }
                    .sources[open] summary::before { transform: rotate(90deg); }
                    .sources .src-list {
                        margin-top: 10px;
                        display: flex;
                        flex-direction: column;
                        gap: 8px;
                    }
                    .sources .src-item {
                        padding: 10px 14px;
                        background: var(--bg-card);
                        border: 1px solid var(--border);
                        border-radius: var(--radius-sm);
                        font-size: 12px;
                        color: var(--text-muted);
                        line-height: 1.55;
                    }
                    .sources .src-item .src-meta {
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        margin-bottom: 4px;
                    }
                    .sources .src-item .src-meta .src-idx {
                        font-weight: 600;
                        color: var(--accent);
                        font-size: 11px;
                        min-width: 18px;
                    }
                    .sources .src-item .src-meta .src-file {
                        font-weight: 500;
                        color: var(--text);
                    }
                    .sources .src-item .src-meta .src-score {
                        color: var(--text-faint);
                        font-size: 11px;
                    }
                    .sources .src-item .src-snippet {
                        color: var(--text-faint);
                        font-style: italic;
                    }

                    /* ── Error / Toast ── */
                    .error-msg {
                        padding: 14px 18px;
                        background: #261A18;
                        border: 1px solid #3E2622;
                        border-radius: var(--radius-sm);
                        color: #E8907A;
                        font-size: 14px;
                        line-height: 1.5;
                    }
                    .toast {
                        position: fixed;
                        bottom: 100px;
                        left: 50%;
                        transform: translateX(-50%);
                        padding: 10px 22px;
                        background: #2C221C;
                        border: 1px solid var(--accent);
                        border-radius: 20px;
                        color: var(--accent);
                        font-size: 13px;
                        z-index: 300;
                        opacity: 0;
                        transition: opacity 0.25s;
                        pointer-events: none;
                    }
                    .toast.show { opacity: 1; }

                    /* ── Loading dots ── */
                    .loading-dots {
                        display: flex;
                        align-items: center;
                        gap: 6px;
                        padding: 12px 0 20px;
                    }
                    .loading-dots .dot {
                        width: 5px; height: 5px;
                        border-radius: 50%;
                        background: var(--accent);
                        animation: bounce 0.5s ease-in-out infinite;
                    }
                    .loading-dots .dot:nth-child(1) { animation-delay: 0.0s; }
                    .loading-dots .dot:nth-child(2) { animation-delay: 0.12s; }
                    .loading-dots .dot:nth-child(3) { animation-delay: 0.24s; }

                    @keyframes bounce {
                        0%, 60%, 100% { opacity: 0.3; transform: translateY(0); }
                        30%           { opacity: 1.0; transform: translateY(-6px); }
                    }

                    /* ── Input bar ── */
                    .input-bar-wrap {
                        position: fixed;
                        bottom: 0;
                        left: 0; right: 0;
                        background: linear-gradient(to top, var(--bg) 80%, transparent);
                        padding: 10px 20px 24px;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        gap: 0;
                        z-index: 100;
                    }
                    .input-bar {
                        display: flex;
                        gap: 10px;
                        width: 100%;
                        max-width: var(--max-w);
                    }
                    .input-bar input {
                        flex: 1;
                        padding: 13px 18px;
                        font-size: 15px;
                        font-family: inherit;
                        color: var(--text);
                        background: var(--bg-card);
                        border: 1px solid var(--border);
                        border-radius: var(--radius);
                        outline: none;
                        box-shadow: var(--shadow);
                        transition: border-color 0.18s ease;
                    }
                    .input-bar input::placeholder { color: var(--text-faint); }
                    .input-bar input:focus {
                        border-color: var(--accent);
                        box-shadow: 0 1px 8px rgba(217,119,87,0.10);
                    }
                    .input-bar button {
                        padding: 13px 24px;
                        font-size: 15px;
                        font-family: inherit;
                        font-weight: 600;
                        color: #FFFFFF;
                        background: var(--accent);
                        border: none;
                        border-radius: var(--radius);
                        cursor: pointer;
                        box-shadow: var(--shadow);
                        transition: background 0.18s ease, transform 0.10s ease;
                        white-space: nowrap;
                    }
                    .input-bar button:hover { background: var(--accent-hover); }
                    .input-bar button:active { transform: scale(0.97); }
                    .input-bar button:disabled {
                        background: #3A3A3A;
                        color: #777;
                        cursor: not-allowed;
                        transform: none;
                    }

                    /* ── Responsive ── */
                    @media (max-width: 600px) {
                        .header { padding: 36px 16px 24px; }
                        .header .logo { font-size: 22px; }
                        .main { padding: 0 14px 110px; }
                        .input-bar-wrap { padding: 8px 14px 18px; }
                        .input-bar input { padding: 11px 14px; font-size: 14px; }
                        .input-bar button { padding: 11px 18px; font-size: 14px; }
                        .drag-overlay .drop-zone { padding: 32px 40px; }
                        .msg-avatar { width: 32px; height: 32px; }
                        .msg-bubble { max-width: 88%; font-size: 14px; padding: 10px 14px; }
                        .msg-row.ai .msg-bubble h3 { font-size: 16px; }
}
                </style>
            </head>
            <body>

            <div class="page">

            <!-- ── Header ── -->
            <div class="header">
                <div class="logo">Mochi</div>
            </div>

            <!-- ── Drag overlay ── -->
            <div class="drag-overlay" id="dragOverlay">
                <div class="drop-zone">
                    <div class="drop-icon">⬆</div>
                    <div class="drop-text">松开以添加文件</div>
                    <div class="drop-ext">支持 .txt 和 .pdf</div>
                </div>
            </div>

            <!-- ── Toast ── -->
            <div class="toast" id="toast"></div>

            <!-- ── Conversation area ── -->
            <div class="main" id="chatArea">
                <div class="welcome" id="welcomeBox">
                    <div class="greeting">
                        <span class="star">✦</span>
                        <span class="text" id="greetingText">Good afternoon</span>
                    </div>
                </div>
            </div>

            <!-- ── Uploaded file card ── -->
            <div class="file-card-wrap" id="fileCardWrap" style="display:none;">
                <div class="file-card" id="fileCard">
                    <span class="file-icon">📄</span>
                    <span class="file-name" id="fileNameSpan"></span>
                    <span class="file-status" id="fileStatus"></span>
                    <button class="file-remove" id="fileRemoveBtn" title="移除文件">✕</button>
                </div>
            </div>

            <!-- ── Fixed input bar ── -->
            <div class="input-bar-wrap">
                <div class="input-bar">
                    <input type="text" id="questionInput"
                           placeholder="输入你的问题，或拖入文件开始…"
                           onkeydown="if(event.key==='Enter') askQuestion()"
                           autocomplete="off">
                    <button id="askButton" onclick="askQuestion()">发送</button>
                </div>
            </div>

            </div><!-- /page -->

            <script>
                /* ── Globals ── */
                var uploadedFileName = null;

                /* ── Time-based greeting ── */
                (function(){
                    var h = new Date().getHours();
                    var t = h >= 5 && h < 12 ? 'Good morning'
                          : h >= 12 && h < 18 ? 'Good afternoon'
                          : 'Good evening';
                    document.getElementById('greetingText').textContent = t;
                })();

                /* ── Toast ── */
                function showToast(msg, ms) {
                    var el = document.getElementById('toast');
                    el.textContent = msg;
                    el.classList.add('show');
                    clearTimeout(el._tid);
                    el._tid = setTimeout(function(){ el.classList.remove('show'); }, ms || 2500);
                }

                /* ── File card visibility ── */
                function showFileCard(name, status) {
                    document.getElementById('fileNameSpan').textContent = name;
                    document.getElementById('fileStatus').textContent = status || '';
                    document.getElementById('fileCardWrap').style.display = 'flex';
                }
                function hideFileCard() {
                    document.getElementById('fileCardWrap').style.display = 'none';
                }

                /* ── Upload file to server ── */
                async function uploadFile(fileName, content, type) {
                    showFileCard(fileName, '正在解析…');
                    var resp = await fetch('/api/upload', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json; charset=utf-8' },
                        body: JSON.stringify({ fileName: fileName, content: content, type: type })
                    });
                    var data = await resp.json();
                    if (!resp.ok || data.error) {
                        showToast(data.error || '上传失败', 3000);
                        hideFileCard();
                        return false;
                    }
                    uploadedFileName = fileName;
                    document.getElementById('fileStatus').textContent = '已就绪，可开始提问';
                    showToast('文件已就绪（' + data.chunks + ' 个片段），可以开始提问', 3000);
                    return true;
                }

                /* ── Drag & drop ── */
                var dragOverlay = document.getElementById('dragOverlay');
                var dragCounter = 0;

                function handleDragEnter(e) {
                    e.preventDefault(); e.stopPropagation();
                    dragCounter++;
                    dragOverlay.classList.add('active');
                }
                function handleDragLeave(e) {
                    e.preventDefault(); e.stopPropagation();
                    dragCounter--;
                    if (dragCounter <= 0) { dragCounter = 0; dragOverlay.classList.remove('active'); }
                }
                function handleDragOver(e) {
                    e.preventDefault(); e.stopPropagation();
                }

                async function handleDrop(e) {
                    e.preventDefault(); e.stopPropagation();
                    dragCounter = 0;
                    dragOverlay.classList.remove('active');

                    var file = (e.dataTransfer.files && e.dataTransfer.files[0]);
                    if (!file) return;

                    var name = file.name;
                    var ext = name.split('.').pop().toLowerCase();

                    if (ext !== 'txt' && ext !== 'pdf') {
                        showToast('仅支持 .txt 和 .pdf 文件', 3000);
                        return;
                    }

                    if (ext === 'txt') {
                        var reader = new FileReader();
                        reader.onload = function(ev) {
                            uploadFile(name, ev.target.result, 'txt');
                        };
                        reader.onerror = function() { showToast('读取文件失败', 3000); };
                        reader.readAsText(file, 'UTF-8');
                    } else {
                        // pdf: 前端读成 base64，后端解析
                        showFileCard(name, '正在读取…');
                        var reader = new FileReader();
                        reader.onload = function(ev) {
                            var b64 = ev.target.result.split(',')[1]; // strip "data:...;base64,"
                            uploadFile(name, b64, 'pdf');
                        };
                        reader.onerror = function() { showToast('读取文件失败', 3000); hideFileCard(); };
                        reader.readAsDataURL(file);
                    }
                }

                document.addEventListener('dragenter', handleDragEnter);
                document.addEventListener('dragleave', handleDragLeave);
                document.addEventListener('dragover', handleDragOver);
                document.addEventListener('drop', handleDrop);

                // Remove file button
                document.getElementById('fileRemoveBtn').addEventListener('click', function(){
                    uploadedFileName = null;
                    hideFileCard();
                });

                /* ── Avatar helper ── */
                function userAvatar() {
                    return '<div class="msg-avatar">' +
                        '<img src="/static/user-avatar.png" alt="" onerror="this.remove()">' +
                        '<span class="avatar-placeholder">&#x1F3AE;</span>' +
                    '</div>';
                }
                function aiAvatar() {
                    return '<div class="msg-avatar">' +
                        '<img src="/static/ai-avatar.png" alt="" onerror="this.remove()">' +
                        '<span class="avatar-placeholder">✦</span>' +
                    '</div>';
                }

                /* ── Send question ── */
                async function askQuestion() {
                    var input = document.getElementById('questionInput');
                    var button = document.getElementById('askButton');
                    var chatArea = document.getElementById('chatArea');
                    var question = input.value.trim();
                    if (!question) return;

                    input.disabled = true;
                    button.disabled = true;
                    button.textContent = '...';

                    var welcome = document.getElementById('welcomeBox');
                    if (welcome) welcome.remove();

                    // User bubble row
                    var userRow = document.createElement('div');
                    userRow.className = 'msg-row user';
                    userRow.innerHTML =
                        userAvatar() +
                        '<div class="msg-bubble">' + escapeHtml(question) + '</div>';
                    chatArea.appendChild(userRow);

                    // AI loading row
                    var aiRow = document.createElement('div');
                    aiRow.className = 'msg-row ai';
                    aiRow.innerHTML =
                        aiAvatar() +
                        '<div class="msg-bubble">' +
                            '<div class="loading-dots">' +
                                '<div class="dot"></div><div class="dot"></div><div class="dot"></div>' +
                            '</div>' +
                        '</div>';
                    chatArea.appendChild(aiRow);

                    window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });

                    try {
                        var resp = await fetch('/api/ask', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                            body: 'question=' + encodeURIComponent(question)
                        });
                        var data = await resp.json();

                        var bubble = aiRow.querySelector('.msg-bubble');
                        bubble.innerHTML = '';

                        if (data.error) {
                            bubble.innerHTML = '<div class="error-msg">' + escapeHtml(data.error) + '</div>';
                        } else {
                            bubble.innerHTML = renderMarkdown(data.answer);

                            // Sources inside the AI bubble, below the answer
                            if (data.sources && data.sources.length > 0) {
                                var details = document.createElement('details');
                                details.className = 'sources';
                                var summary = document.createElement('summary');
                                summary.textContent = '参考来源 (' + data.sources.length + ')';
                                details.appendChild(summary);

                                var list = document.createElement('div');
                                list.className = 'src-list';
                                data.sources.forEach(function(src, i) {
                                    list.innerHTML +=
                                        '<div class="src-item">' +
                                            '<div class="src-meta">' +
                                                '<span class="src-idx">[' + (i + 1) + ']</span>' +
                                                '<span class="src-file">' + escapeHtml(src.fileName || '未知文件') + '</span>' +
                                                '<span class="src-score">相关度 ' + (src.score * 100).toFixed(0) + '%</span>' +
                                            '</div>' +
                                            '<div class="src-snippet">"' + escapeHtml(src.snippet || '') + '"</div>' +
                                        '</div>';
                                });
                                details.appendChild(list);
                                bubble.appendChild(details);
                            }
                        }
                    } catch (err) {
                        var bubble2 = aiRow.querySelector('.msg-bubble');
                        bubble2.innerHTML = '<div class="error-msg">请求失败：' + escapeHtml(err.message) + '</div>';
                    }

                    input.disabled = false;
                    button.disabled = false;
                    button.textContent = '发送';
                    input.value = '';
                    input.focus();
                    window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
                }

                /* ── Markdown → HTML ── */
                function renderMarkdown(text) {
                    if (!text) return '';
                    var lines = text.split('\\n');
                    var html = '';
                    var inList = false, listType = '';
                    for (var i = 0; i < lines.length; i++) {
                        var line = lines[i];
                        if (line.trim() === '') {
                            if (inList) { html += listType === 'ul' ? '</ul>' : '</ol>'; inList = false; listType = ''; }
                            continue;
                        }
                        var hMatch = line.match(/^(#{2,3})\\s+(.+)$/);
                        if (hMatch) {
                            if (inList) { html += listType === 'ul' ? '</ul>' : '</ol>'; inList = false; listType = ''; }
                            html += '<h' + hMatch[1].length + '>' + inlineMarkdown(hMatch[2]) + '</h' + hMatch[1].length + '>';
                            continue;
                        }
                        var ulMatch = line.match(/^[\\-\\*]\\s+(.+)$/);
                        if (ulMatch) {
                            if (!inList || listType !== 'ul') { if (inList) html += listType === 'ul' ? '</ul>' : '</ol>'; html += '<ul>'; inList = true; listType = 'ul'; }
                            html += '<li>' + inlineMarkdown(ulMatch[1]) + '</li>';
                            continue;
                        }
                        var olMatch = line.match(/^\\d+\\.\\s+(.+)$/);
                        if (olMatch) {
                            if (!inList || listType !== 'ol') { if (inList) html += listType === 'ul' ? '</ul>' : '</ol>'; html += '<ol>'; inList = true; listType = 'ol'; }
                            html += '<li>' + inlineMarkdown(olMatch[1]) + '</li>';
                            continue;
                        }
                        if (inList) { html += listType === 'ul' ? '</ul>' : '</ol>'; inList = false; listType = ''; }
                        html += '<p>' + inlineMarkdown(line) + '</p>';
                    }
                    if (inList) { html += listType === 'ul' ? '</ul>' : '</ol>'; }
                    return html;
                }
                function inlineMarkdown(text) {
                    return text.replace(/\\*\\*(.+?)\\*\\*/g, '<strong>$1</strong>').replace(/\\*(.+?)\\*/g, '<em>$1</em>');
                }
                function escapeHtml(text) {
                    var div = document.createElement('div');
                    div.textContent = text;
                    return div.innerHTML;
                }
            </script>
            </body>
            </html>
            """;
    }
}
