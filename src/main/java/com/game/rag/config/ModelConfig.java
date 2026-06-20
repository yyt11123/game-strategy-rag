package com.game.rag.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.time.Duration;

/**
 * 大模型和向量化模型的初始化配置。
 *
 * 核心思路：通义千问(qwen-plus)和 text-embedding-v4 都走阿里云百炼的 OpenAI 兼容接口，
 * 因此统一使用 LangChain4j 的 OpenAiChatModel 和 OpenAiEmbeddingModel，
 * 共用同一个 base_url 和 API Key。不需要引入 DashScope 原生 SDK。
 *
 * 地域说明（非常重要）：
 * - 北京：https://dashscope.aliyuncs.com/compatible-mode/v1
 * - 新加坡：https://dashscope-intl.aliyuncs.com/compatible-mode/v1
 * - 美国弗吉尼亚：https://dashscope-us.aliyuncs.com/compatible-mode/v1
 * API Key 必须和 base_url 地域匹配，否则会鉴权失败。
 */
public class ModelConfig {

    // ==================== 可配置项 ====================

    /**
     * 阿里云百炼 OpenAI 兼容接口的 base URL。
     * 默认使用北京地域。如果 API Key 属于其它地域，请修改此常量。
     */
    public static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /**
     * 大模型名称 —— 通义千问 qwen-plus
     */
    public static final String CHAT_MODEL_NAME = "qwen-plus";

    /**
     * 向量化模型名称 —— text-embedding-v4
     */
    public static final String EMBEDDING_MODEL_NAME = "text-embedding-v4";

    /**
     * API Key 环境变量名。
     * 严禁硬编码 Key！必须通过环境变量传入。
     */
    public static final String ENV_API_KEY = "DASHSCOPE_API_KEY";

    /**
     * 向量维度（text-embedding-v4 支持 1024，也可不设使用默认值）
     */
    public static final Integer EMBEDDING_DIMENSIONS = 1024;

    /**
     * HTTP 请求超时时间（秒），网络不好时可适当调大
     */
    public static final int TIMEOUT_SECONDS = 60;

    /**
     * 最大重试次数：遇到网络抖动或限流(HTTP 429)时的重试上限
     */
    public static final int MAX_RETRIES = 3;

    // ==================== 模型实例（单例，延迟初始化） ====================

    private static volatile ChatModel chatModel;
    private static volatile EmbeddingModel embeddingModel;
    private static final Object chatLock = new Object();
    private static final Object embedLock = new Object();

    /**
     * 获取或初始化大模型（ChatModel）实例。
     * 使用双重检查锁定确保线程安全的单例。
     *
     * @return qwen-plus 对话模型实例
     * @throws IllegalStateException 如果环境变量 DASHSCOPE_API_KEY 未设置
     */
    public static ChatModel getChatModel() {
        if (chatModel == null) {
            synchronized (chatLock) {
                if (chatModel == null) {
                    String apiKey = getApiKey();
                    chatModel = OpenAiChatModel.builder()
                            .apiKey(apiKey)
                            .baseUrl(BASE_URL)
                            .modelName(CHAT_MODEL_NAME)
                            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                            .maxRetries(MAX_RETRIES)       // 自动重试，应对限流和网络抖动
                            .temperature(0.3)               // 较低温度，让回答更确定、更少编造
                            .logRequests(true)              // 开发调试用，可在 logback.xml 控制级别
                            .logResponses(true)
                            .build();
                    System.out.println("✅ 大模型初始化完成：" + CHAT_MODEL_NAME);
                    System.out.println("   baseUrl: " + BASE_URL);
                }
            }
        }
        return chatModel;
    }

    /**
     * 获取或初始化向量化模型（EmbeddingModel）实例。
     * 同样使用双重检查锁定。
     *
     * @return text-embedding-v4 向量化模型实例
     * @throws IllegalStateException 如果环境变量 DASHSCOPE_API_KEY 未设置
     */
    public static EmbeddingModel getEmbeddingModel() {
        if (embeddingModel == null) {
            synchronized (embedLock) {
                if (embeddingModel == null) {
                    String apiKey = getApiKey();
                    embeddingModel = OpenAiEmbeddingModel.builder()
                            .apiKey(apiKey)
                            .baseUrl(BASE_URL)
                            .modelName(EMBEDDING_MODEL_NAME)
                            .dimensions(EMBEDDING_DIMENSIONS)  // 指定向量维度
                            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                            .maxRetries(MAX_RETRIES)            // 自动重试
                            .logRequests(true)
                            .logResponses(true)
                            .build();
                    System.out.println("✅ 向量化模型初始化完成：" + EMBEDDING_MODEL_NAME);
                    System.out.println("   维度：" + EMBEDDING_DIMENSIONS);
                }
            }
        }
        return embeddingModel;
    }

    /**
     * 从环境变量读取 API Key。
     * 如果未设置，抛出明确的中文错误提示。
     *
     * @return API Key 字符串
     * @throws IllegalStateException 环境变量未设置时抛出
     */
    private static String getApiKey() {
        String apiKey = System.getenv(ENV_API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "\n========================================\n" +
                    "❌ 未找到环境变量 " + ENV_API_KEY + "\n\n" +
                    "请在系统中设置此环境变量，值为你在阿里云百炼创建的 API Key。\n\n" +
                    "【Windows 设置方法】\n" +
                    "1. 右键【此电脑】 -> 属性 -> 高级系统设置 -> 环境变量\n" +
                    "2. 在【用户变量】中新建：\n" +
                    "   变量名：DASHSCOPE_API_KEY\n" +
                    "   变量值：sk-xxxxxxxxxxxxxxxxxxxxxxxx\n" +
                    "3. 确定保存后，必须重启 IDE / 终端才能生效！\n\n" +
                    "【临时方案（仅当前终端有效）】\n" +
                    "PowerShell: $env:DASHSCOPE_API_KEY='sk-xxx'\n" +
                    "CMD:       set DASHSCOPE_API_KEY=sk-xxx\n" +
                    "========================================\n"
            );
        }
        return apiKey.trim();
    }
}
