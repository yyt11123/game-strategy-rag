package com.game.rag.rag;

/**
 * 检索结果：包含命中的文本片段、相关性分数和来源文件名。
 * 在回答时会把这些信息一起传给大模型，并展示给用户。
 *
 * @param content  命中的文本片段内容
 * @param score    相关性分数（0~1，越高越相关）
 * @param fileName 来源文件名（如 "sample_strategy.txt"）
 */
public record SearchResult(
        String content,
        double score,
        String fileName
) {}
