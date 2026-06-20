package com.game.rag.web;

import com.game.rag.rag.RagAnswer;
import com.game.rag.rag.RagService;
import com.game.rag.rag.SearchResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

    public WebServer(RagService ragService) throws IOException {
        this.ragService = ragService;
        this.server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // 注册路由
        server.createContext("/", this::handleIndex);       // 首页
        server.createContext("/api/ask", this::handleAsk);  // 问答 API

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
        // 只接受 POST 请求
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "application/json",
                    "{\"error\":\"只支持POST请求\"}");
            return;
        }

        // 读取请求体
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        // 解析 question 参数（简单 form-urlencoded 解析）
        String question = parseQuestion(body);
        if (question == null || question.isBlank()) {
            sendResponse(exchange, 400, "application/json",
                    "{\"error\":\"问题不能为空\"}");
            return;
        }

        // 调用 RAG 服务
        try {
            RagAnswer answer = ragService.ask(question);
            String json = buildJsonResponse(answer);
            sendResponse(exchange, 200, "application/json; charset=utf-8", json);
        } catch (Exception e) {
            String errorJson = "{\"error\":\"处理请求时出错：" + escapeJson(e.getMessage()) + "\"}";
            sendResponse(exchange, 500, "application/json", errorJson);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 form-urlencoded 请求体中解析 question 参数。
     */
    private String parseQuestion(String body) {
        // 简单处理：question=xxx 或直接是纯文本
        if (body.startsWith("question=")) {
            String value = body.substring("question=".length());
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        // 也支持 JSON 格式：{"question":"xxx"}
        if (body.contains("\"question\"")) {
            int start = body.indexOf("\"question\"");
            int colon = body.indexOf(":", start);
            int valStart = body.indexOf("\"", colon + 1);
            int valEnd = body.indexOf("\"", valStart + 1);
            if (valStart > 0 && valEnd > valStart) {
                return body.substring(valStart + 1, valEnd);
            }
        }
        return body.trim();
    }

    /**
     * 构建 JSON 响应。
     */
    private String buildJsonResponse(RagAnswer answer) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"answer\": \"").append(escapeJson(answer.answer())).append("\",\n");
        json.append("  \"sources\": [\n");

        List<SearchResult> sources = answer.sources();
        for (int i = 0; i < sources.size(); i++) {
            SearchResult src = sources.get(i);
            if (i > 0) json.append(",\n");
            json.append("    {\n");
            json.append("      \"fileName\": \"").append(
                    escapeJson(src.fileName() != null ? src.fileName() : "未知")).append("\",\n");
            json.append("      \"score\": ").append(String.format("%.4f", src.score())).append(",\n");

            // 片段内容（截取前 300 字符展示）
            String snippet = src.content();
            if (snippet.length() > 300) {
                snippet = snippet.substring(0, 300) + "...";
            }
            json.append("      \"snippet\": \"").append(escapeJson(snippet)).append("\"\n");
            json.append("    }");
        }

        json.append("\n  ]\n");
        json.append("}");
        return json.toString();
    }

    /**
     * 对 JSON 字符串中的特殊字符进行转义。
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 统一发送 HTTP 响应。
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");  // 允许跨域
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
                <title>攻略小帮手 · 游戏攻略智能问答</title>
                <style>
                    :root {
                        --bg:         #1A1A1A;
                        --bg-card:    #262626;
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
                        padding: 48px 20px 32px;
                    }

                    .header .logo {
                        font-size: 28px;
                        font-weight: 700;
                        color: var(--text);
                        letter-spacing: -0.3px;
                        margin-bottom: 6px;
                    }
                    .header .logo .accent { color: var(--accent); }

                    .header .subtitle {
                        font-size: 14px;
                        color: var(--text-muted);
                        font-weight: 400;
                    }

                    .main {
                        flex: 1;
                        width: 100%;
                        max-width: var(--max-w);
                        margin: 0 auto;
                        padding: 0 20px 120px;  /* 120px bottom for fixed input */
                    }

                    /* ── Welcome / Empty state ── */
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

                    /* ── Conversation ── */
                    .conv-item {
                        margin-bottom: 32px;
                    }

                    /* User message — right-aligned feel via a subtle warm left stripe */
                    .user-msg {
                        display: flex;
                        align-items: flex-start;
                        gap: 10px;
                        margin-bottom: 18px;
                    }
                    .user-msg .avatar {
                        flex-shrink: 0;
                        width: 30px; height: 30px;
                        border-radius: 50%;
                        background: var(--bg);
                        border: 1px solid var(--border);
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        font-size: 15px;
                        color: var(--text-muted);
                    }
                    .user-msg .body {
                        flex: 1;
                        padding: 12px 16px;
                        border-radius: var(--radius-sm);
                        font-size: 15px;
                        color: var(--text);
                        border: 1px solid transparent;
                        background: transparent;
                    }

                    /* AI answer */
                    .ai-msg {
                        padding: 20px 0 0 0;
                    }
                    .ai-msg .content {
                        font-size: 15px;
                        line-height: 1.75;
                        color: var(--text);
                    }

                    /* Markdown-rendered content inside AI answer */
                    .ai-msg .content h3 { font-size: 17px; font-weight: 600; margin: 20px 0 8px; color: var(--text); }
                    .ai-msg .content h4 { font-size: 15px; font-weight: 600; margin: 16px 0 6px; color: var(--text); }
                    .ai-msg .content p  { margin: 0 0 10px; }
                    .ai-msg .content ul, .ai-msg .content ol { margin: 4px 0 12px 20px; }
                    .ai-msg .content li { margin-bottom: 4px; }
                    .ai-msg .content strong { font-weight: 600; color: var(--text); }
                    .ai-msg .content em { font-style: italic; color: var(--text-muted); }

                    /* ── Sources (collapsible) ── */
                    .sources {
                        margin-top: 18px;
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
                    .sources[open] summary::before {
                        transform: rotate(90deg);
                    }
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
                        overflow: hidden;
                        text-overflow: ellipsis;
                    }

                    /* ── Error ── */
                    .error-msg {
                        padding: 14px 18px;
                        background: #261A18;
                        border: 1px solid #3E2622;
                        border-radius: var(--radius-sm);
                        color: #E8907A;
                        font-size: 14px;
                        line-height: 1.5;
                    }

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

                    /* ── Input bar (fixed bottom) ── */
                    .input-bar-wrap {
                        position: fixed;
                        bottom: 0;
                        left: 0; right: 0;
                        background: linear-gradient(to top, var(--bg) 80%, transparent);
                        padding: 10px 20px 24px;
                        display: flex;
                        justify-content: center;
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
                    .input-bar input::placeholder {
                        color: var(--text-faint);
                    }
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
                    .input-bar button:hover {
                        background: var(--accent-hover);
                    }
                    .input-bar button:active {
                        transform: scale(0.97);
                    }
                    .input-bar button:disabled {
                        background: #3A3A3A;
                        color: #777;
                        cursor: not-allowed;
                        transform: none;
                    }

                    /* ── Divider between Q&A rounds ── */
                    .conv-divider {
                        border: none;
                        border-top: 1px solid var(--border);
                        margin: 8px 0 28px;
                        opacity: 0.5;
                    }

                    /* ── Responsive ── */
                    @media (max-width: 600px) {
                        .header { padding: 36px 16px 24px; }
                        .header .logo { font-size: 22px; }
                        .header .subtitle { font-size: 13px; }
                        .main { padding: 0 14px 110px; }
                        .input-bar-wrap { padding: 8px 14px 18px; }
                        .input-bar input { padding: 11px 14px; font-size: 14px; }
                        .input-bar button { padding: 11px 18px; font-size: 14px; }
                        .ai-msg .content h3 { font-size: 16px; }
                    }
                </style>
            </head>
            <body>

            <div class="page">

            <!-- ── Header ── -->
            <div class="header">
                <div class="logo">Mochi</div>
            </div>

            <!-- ── Conversation area ── -->
            <div class="main" id="chatArea">
                <div class="welcome" id="welcomeBox">
                    <div class="greeting">
                        <span class="star">✦</span>
                        <span class="text" id="greetingText">Good afternoon</span>
                    </div>
                </div>
            </div>

            <!-- ── Fixed input bar ── -->
            <div class="input-bar-wrap">
                <div class="input-bar">
                    <input type="text" id="questionInput"
                           placeholder="输入你的问题…"
                           onkeydown="if(event.key==='Enter') askQuestion()"
                           autocomplete="off">
                    <button id="askButton" onclick="askQuestion()">发送</button>
                </div>
            </div>

            </div><!-- /page -->

            <script>
                /* ── Time-based greeting ── */
                (function setGreeting() {
                    var hour = new Date().getHours();
                    var text;
                    if (hour >= 5 && hour < 12)        text = 'Good morning';
                    else if (hour >= 12 && hour < 18)  text = 'Good afternoon';
                    else                               text = 'Good evening';
                    document.getElementById('greetingText').textContent = text;
                })();

                /* ── Send question ── */
                async function askQuestion() {
                    const input = document.getElementById('questionInput');
                    const button = document.getElementById('askButton');
                    const chatArea = document.getElementById('chatArea');
                    const question = input.value.trim();
                    if (!question) return;

                    // Disable input
                    input.disabled = true;
                    button.disabled = true;
                    button.textContent = '...';

                    // Remove welcome box
                    const welcome = document.getElementById('welcomeBox');
                    if (welcome) welcome.remove();

                    // Append: user message + loading dots
                    const conv = document.createElement('div');
                    conv.className = 'conv-item';
                    conv.innerHTML =
                        '<div class="user-msg">' +
                            '<div class="avatar">&#x1F3AE;</div>' +
                            '<div class="body">' + escapeHtml(question) + '</div>' +
                        '</div>' +
                        '<div class="ai-msg">' +
                            '<div class="loading-dots">' +
                                '<div class="dot"></div><div class="dot"></div><div class="dot"></div>' +
                            '</div>' +
                        '</div>';
                    chatArea.appendChild(conv);

                    // Scroll to bottom
                    window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });

                    try {
                        const resp = await fetch('/api/ask', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                            body: 'question=' + encodeURIComponent(question)
                        });
                        const data = await resp.json();

                        // Remove loading dots, render answer
                        const aiMsg = conv.querySelector('.ai-msg');
                        aiMsg.innerHTML = '';

                        if (data.error) {
                            aiMsg.innerHTML = '<div class="error-msg">' + escapeHtml(data.error) + '</div>';
                        } else {
                            // Render answer with simple Markdown
                            const contentDiv = document.createElement('div');
                            contentDiv.className = 'content';
                            contentDiv.innerHTML = renderMarkdown(data.answer);
                            aiMsg.appendChild(contentDiv);

                            // Sources (collapsible)
                            if (data.sources && data.sources.length > 0) {
                                const details = document.createElement('details');
                                details.className = 'sources';
                                const summary = document.createElement('summary');
                                summary.textContent = '参考来源 (' + data.sources.length + ')';
                                details.appendChild(summary);

                                const list = document.createElement('div');
                                list.className = 'src-list';
                                data.sources.forEach((src, i) => {
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
                                aiMsg.appendChild(details);
                            }
                        }
                    } catch (err) {
                        const aiMsg = conv.querySelector('.ai-msg');
                        aiMsg.innerHTML = '<div class="error-msg">请求失败：' + escapeHtml(err.message) + '</div>';
                    }

                    // Restore input
                    input.disabled = false;
                    button.disabled = false;
                    button.textContent = '发送';
                    input.value = '';
                    input.focus();
                    window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
                }

                /* ── Simple Markdown → HTML ── */
                function renderMarkdown(text) {
                    if (!text) return '';

                    // Escape HTML first
                    var lines = text.split('\\n');
                    var html = '';
                    var inList = false;
                    var listType = '';

                    for (var i = 0; i < lines.length; i++) {
                        var line = lines[i];

                        // Blank line: close list, insert paragraph break
                        if (line.trim() === '') {
                            if (inList) {
                                html += listType === 'ul' ? '</ul>' : '</ol>';
                                inList = false;
                                listType = '';
                            }
                            continue;
                        }

                        // Heading: ### or ##
                        var hMatch = line.match(/^(#{2,3})\\s+(.+)$/);
                        if (hMatch) {
                            if (inList) { html += listType === 'ul' ? '</ul>' : '</ol>'; inList = false; listType = ''; }
                            var level = hMatch[1].length;
                            html += '<h' + level + '>' + inlineMarkdown(hMatch[2]) + '</h' + level + '>';
                            continue;
                        }

                        // Unordered list: - item or * item
                        var ulMatch = line.match(/^[\\-\\*]\\s+(.+)$/);
                        if (ulMatch) {
                            if (!inList || listType !== 'ul') {
                                if (inList) html += (listType === 'ul' ? '</ul>' : '</ol>');
                                html += '<ul>';
                                inList = true; listType = 'ul';
                            }
                            html += '<li>' + inlineMarkdown(ulMatch[1]) + '</li>';
                            continue;
                        }

                        // Ordered list: 1. item
                        var olMatch = line.match(/^\\d+\\.\\s+(.+)$/);
                        if (olMatch) {
                            if (!inList || listType !== 'ol') {
                                if (inList) html += (listType === 'ul' ? '</ul>' : '</ol>');
                                html += '<ol>';
                                inList = true; listType = 'ol';
                            }
                            html += '<li>' + inlineMarkdown(olMatch[1]) + '</li>';
                            continue;
                        }

                        // Regular paragraph line
                        if (inList) { html += listType === 'ul' ? '</ul>' : '</ol>'; inList = false; listType = ''; }
                        html += '<p>' + inlineMarkdown(line) + '</p>';
                    }

                    if (inList) { html += listType === 'ul' ? '</ul>' : '</ol>'; }

                    return html;
                }

                /* ── Inline formatting: **bold**, *italic* ── */
                function inlineMarkdown(text) {
                    return text
                        .replace(/\\*\\*(.+?)\\*\\*/g, '<strong>$1</strong>')
                        .replace(/\\*(.+?)\\*/g, '<em>$1</em>');
                }

                /* ── HTML escape ── */
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
