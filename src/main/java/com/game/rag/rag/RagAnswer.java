package com.game.rag.rag;

import java.util.List;

/**
 * RAG 回答：包含大模型生成的回答文本和所使用的攻略来源。
 *
 * @param answer  大模型生成的回答文本
 * @param sources 检索到的攻略片段列表（用于展示"答案来自哪里"）
 */
public record RagAnswer(
        String answer,
        List<SearchResult> sources
) {
    /**
     * 格式化输出：回答内容 + 来源标注。
     * 用于在控制台和 Web 界面中展示。
     */
    public String toFormattedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("📝 回答：\n");
        sb.append(answer).append("\n");

        if (sources != null && !sources.isEmpty()) {
            sb.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("📖 参考来源：\n");
            for (int i = 0; i < sources.size(); i++) {
                SearchResult src = sources.get(i);
                sb.append("  [").append(i + 1).append("] ");
                if (src.fileName() != null && !src.fileName().isBlank()) {
                    sb.append("文件：").append(src.fileName()).append("  ");
                    sb.append("（相关度：").append(String.format("%.2f", src.score())).append("）\n");
                }
                // 只展示片段的前 200 字符，避免来源信息过长
                String snippet = src.content();
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "...";
                }
                sb.append("     \"\"\"").append(snippet).append("\"\"\"\n");
            }
        }

        return sb.toString();
    }
}
