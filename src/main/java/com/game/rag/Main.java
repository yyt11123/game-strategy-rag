package com.game.rag;

import com.game.rag.agent.GameAssistantAgent;
import com.game.rag.config.ModelConfig;
import com.game.rag.rag.KnowledgeBase;
import com.game.rag.rag.RagAnswer;
import com.game.rag.rag.RagService;
import com.game.rag.web.WebServer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 游戏攻略智能问答助手 — 主入口。
 *
 * 运行模式（通过命令行参数切换）：
 * - 无参数 / "phase0"：只验证 API 连通性
 * - "phase1"：完整 RAG 模式（检索 → 生成 → 带来源的回答）
 * - "phase2"：Agent 模式（大模型自主选择查攻略还是计算）
 *
 * 所有阶段都包含阶段 0 的 API 验证。
 */
public class Main {

    /** 知识库实例（全局单例） */
    private static KnowledgeBase knowledgeBase;

    /** RAG 服务 */
    private static RagService ragService;

    /** Agent 对话接口（阶段 2） */
    private static GameAssistantAgent.Assistant agent;

    /** Web 服务（阶段 3） */
    private static WebServer webServer;

    public static void main(String[] args) {
        // 🔧 修复 Windows 控制台中文乱码（JDK 18+ 关键做法）
        //     在 JDK 18+ 中，System.out/err 的编码由 stdout.encoding / stderr.encoding
        //     系统属性决定，而非 file.encoding。但即便设了这两个属性，
        //     JVM 内建的 PrintStream 可能仍然附着在旧的 Charset 编码器上。
        //     因此这里用 FileDescriptor.out / FileDescriptor.err 重建一个全新的
        //     UTF-8 PrintStream —— 这会绕过 JVM 启动时设置的控制台编码，直接以
        //     UTF-8 字节写入 OS 文件描述符，从根本上杜绝乱码。
        //     注意：这段代码必须在 main() 的第一行执行，在任何中文 print 之前。
        try {
            PrintStream utf8Out = new PrintStream(
                    new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
            PrintStream utf8Err = new PrintStream(
                    new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
            System.setOut(utf8Out);
            System.setErr(utf8Err);
        } catch (Exception ignored) {
            // 如果设置失败（极少见），继续用默认编码
        }

        printBanner();

        // 解析运行模式
        String mode = args.length > 0 ? args[0].toLowerCase() : "phase0";

        // ==================== 所有阶段公共：验证 API ====================
        if (!verifyApis()) {
            System.exit(1);
        }

        // ==================== 阶段 1/2/Web 公共：构建知识库 ====================
        if (mode.equals("phase1") || mode.equals("phase2") || mode.equals("web")) {
            try {
                knowledgeBase = new KnowledgeBase();
                int count = knowledgeBase.build();
                if (count == 0) {
                    System.out.println("⚠️ 知识库为空，将只能回答'未找到相关信息'。");
                }
                ragService = new RagService(knowledgeBase);
            } catch (Exception e) {
                System.err.println("❌ 知识库构建失败：");
                e.printStackTrace(System.err);
                System.out.println("\n将继续运行，但检索功能不可用。");
            }

            // 如果是阶段 2，初始化 Agent
            if (mode.equals("phase2")) {
                System.out.println("🤖 正在初始化 Agent 模式...");
                try {
                    agent = GameAssistantAgent.create(knowledgeBase);
                    System.out.println("✅ Agent 初始化完成（已注册：查攻略工具 + 计算工具）\n");
                } catch (Exception e) {
                    System.err.println("⚠️ Agent 初始化失败，回退到普通 RAG 模式：" + e.getMessage());
                    mode = "phase1";
                }
            }
        }

        // ==================== 进入交互模式 ====================
        if (mode.equals("phase1")) {
            runRagLoop();
        } else if (mode.equals("phase2")) {
            runAgentLoop();
        } else if (mode.equals("web")) {
            runWebMode();
        } else {
            // phase0：只验证 API，打印成功信息后退出
            System.out.println("═══════════════════════════════════════");
            System.out.println("  🎉 阶段 0 验证全部通过！");
            System.out.println("  大模型和向量化 API 都已成功接通。");
            System.out.println();
            System.out.println("  使用方法：");
            System.out.println("    mvn exec:java                          → 阶段0：验证API");
            System.out.println("    mvn exec:java -Dexec.args=\"phase1\"     → 阶段1：完整RAG");
            System.out.println("    mvn exec:java -Dexec.args=\"phase2\"     → 阶段2：Agent模式");
            System.out.println("    mvn exec:java -Dexec.args=\"web\"        → 阶段3：Web界面");
            System.out.println("═══════════════════════════════════════");
        }
    }

    // ==================== 阶段 0：API 验证 ====================

    /**
     * 验证大模型和向量化 API 是否都能正常连通。
     * @return true 表示全部通过
     */
    private static boolean verifyApis() {
        System.out.println("【阶段0】验证 API 连通性...\n");

        try {
            // --- 测试 1：大模型对话 ---
            System.out.println(">>> 测试 1：大模型对话 (qwen-plus)");
            ChatModel chatModel = ModelConfig.getChatModel();
            String reply = chatModel.chat("请用一句话介绍你自己，不超过30个字。");
            System.out.println("📝 大模型回复：" + reply);
            System.out.println("✅ 大模型 API 测试通过！\n");

            // --- 测试 2：向量化 ---
            System.out.println(">>> 测试 2：向量化 (text-embedding-v4)");
            EmbeddingModel embeddingModel = ModelConfig.getEmbeddingModel();
            String testText = "这是一段测试文本，用于验证向量化API是否正常工作。";
            Embedding embedding = embeddingModel.embed(testText).content();
            int dimension = embedding.vector().length;
            System.out.println("📐 向量维度：" + dimension);
            System.out.println("📐 向量前 5 个分量：" + formatVector(embedding.vector(), 5));
            System.out.println("✅ 向量化 API 测试通过！\n");

            return true;

        } catch (Exception e) {
            System.err.println("❌ API 验证失败：");
            System.err.println("   错误类型：" + e.getClass().getSimpleName());
            System.err.println("   错误信息：" + e.getMessage());
            System.err.println("\n请检查：");
            System.err.println("  1. 环境变量 DASHSCOPE_API_KEY 是否已正确设置");
            System.err.println("  2. API Key 所属地域是否与 baseUrl 匹配");
            System.err.println("     当前 baseUrl：" + ModelConfig.BASE_URL);
            System.err.println("     北京地域 Key → 北京 baseUrl");
            System.err.println("     新加坡地域 Key → 新加坡 baseUrl");
            System.err.println("     美国地域 Key → 美国 baseUrl");
            System.err.println("  3. 网络是否能访问 dashscope.aliyuncs.com");
            System.err.println("  4. 百炼账户是否有可用额度");
            return false;
        }
    }

    // ==================== 阶段 1：RAG 问答循环 ====================

    /**
     * 阶段 1 的交互循环：纯 RAG 模式。
     * 用户输入问题 → 检索知识库 → 生成带来源的回答。
     */
    private static void runRagLoop() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  阶段 1：RAG 问答模式                        ║");
        System.out.println("║  输入问题，获取基于攻略的回答+来源            ║");
        System.out.println("║  输入 /quit 退出   输入 /help 查看帮助        ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        // 🔧 用 UTF-8 编码读取控制台输入，避免中文乱码
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            while (true) {
                System.out.print("\n🎮 请输入你的问题：\n> ");
                System.out.flush();
                String input = reader.readLine();
                if (input == null) break;
                input = input.trim();

                if (input.isEmpty()) continue;

                // 处理命令
                if (input.equals("/quit") || input.equals("/exit")) {
                    System.out.println("👋 再见！");
                    break;
                }
                if (input.equals("/help")) {
                    printHelp("phase1");
                    continue;
                }

                // RAG 问答
                try {
                    System.out.println("\n⏳ 正在检索攻略并生成回答...");
                    RagAnswer answer = ragService.ask(input);
                    System.out.println("\n" + answer.toFormattedString());
                } catch (Exception e) {
                    System.err.println("❌ 处理问题时出错：" + e.getMessage());
                    System.err.println("   请检查网络连接和 API 额度。");
                }
            }
        } catch (IOException e) {
            System.err.println("❌ 读取输入时出错：" + e.getMessage());
        }
    }

    // ==================== 阶段 2：Agent 问答循环 ====================

    /**
     * 阶段 2 的交互循环：Agent 模式。
     * 大模型自主判断该查攻略还是该算数，并调用对应工具。
     *
     * 控制台会打印每次工具调用的信息（通过日志），
     * 方便在答辩时展示 Agent 的工作过程。
     */
    private static void runAgentLoop() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  阶段 2：Agent 智能问答模式                  ║");
        System.out.println("║  AI 自主选择：查攻略 或 计算                  ║");
        System.out.println("║  输入 /quit 退出   输入 /help 查看帮助        ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        // 🔧 用 UTF-8 编码读取控制台输入，避免中文乱码
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            while (true) {
                System.out.print("\n🎮 请输入你的问题：\n> ");
                System.out.flush();
                String input = reader.readLine();
                if (input == null) break;
                input = input.trim();

                if (input.isEmpty()) continue;

                // 处理命令
                if (input.equals("/quit") || input.equals("/exit")) {
                    System.out.println("👋 再见！");
                    break;
                }
                if (input.equals("/help")) {
                    printHelp("phase2");
                    continue;
                }

                // Agent 问答
                try {
                    System.out.println("\n⏳ Agent 正在分析问题并调用工具...\n");
                    String answer = agent.chat(input);
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    System.out.println("📝 Agent 回答：\n");
                    System.out.println(answer);
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                } catch (Exception e) {
                    System.err.println("❌ Agent 处理问题时出错：" + e.getMessage());
                    System.err.println("   请检查网络连接和 API 额度。");
                }
            }
        } catch (IOException e) {
            System.err.println("❌ 读取输入时出错：" + e.getMessage());
        }
    }

    // ==================== 阶段 3：Web 界面 ====================

    /**
     * Web 模式：启动 HTTP 服务，提供浏览器可访问的网页问答界面。
     *
     * 访问地址：http://localhost:8080
     * 功能：输入问题 → 展示带来源的回答
     */
    private static void runWebMode() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  阶段 3：Web 问答界面                        ║");
        System.out.println("║  启动浏览器访问 http://localhost:8080        ║");
        System.out.println("║  输入 /quit 退出                             ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        try {
            webServer = new WebServer(ragService);
            webServer.start();
        } catch (Exception e) {
            System.err.println("❌ Web 服务启动失败：" + e.getMessage());
            e.printStackTrace();
            return;
        }

        // Web 模式下也保留控制台交互，方便退出
        // 🔧 用 UTF-8 编码读取控制台输入，避免中文乱码
        System.out.println("  输入 /quit 退出程序，输入 /help 查看帮助。");
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            while (true) {
                System.out.print("> ");
                System.out.flush();
                String input = reader.readLine();
                if (input == null) break;
                input = input.trim();
                if (input.equals("/quit") || input.equals("/exit")) {
                    break;
                }
                if (input.equals("/help")) {
                    System.out.println("Web 模式：请在浏览器中访问 http://localhost:8080 进行问答。");
                    System.out.println("输入 /quit 退出。");
                }
            }
        } catch (IOException e) {
            System.err.println("❌ 读取输入时出错：" + e.getMessage());
        }
        webServer.stop();
        System.out.println("👋 再见！");
    }

    // ==================== 辅助方法 ====================

    /**
     * 打印帮助信息。
     */
    private static void printHelp(String mode) {
        System.out.println("\n📖 帮助信息：");
        if (mode.equals("phase1")) {
            System.out.println("  这是 RAG 模式，程序会：");
            System.out.println("  1. 在你的攻略知识库中检索相关内容");
            System.out.println("  2. 将检索到的片段作为参考资料交给大模型");
            System.out.println("  3. 大模型基于攻略内容生成回答并标注来源");
            System.out.println();
            System.out.println("  你可以问：");
            System.out.println("  - \"暗影领主怎么打？\"");
            System.out.println("  - \"星光草在哪刷？\"");
            System.out.println("  - \"冰霜巨龙推荐什么阵容？\"");
            System.out.println("  - \"暗影精华有什么用？\"");
        } else {
            System.out.println("  这是 Agent 模式，AI 会自主判断：");
            System.out.println("  - 查攻略问题 → 调用'查攻略工具'");
            System.out.println("  - 计算问题 → 调用'计算工具'");
            System.out.println("  - 复合问题 → 先查攻略，再计算");
            System.out.println();
            System.out.println("  你可以问：");
            System.out.println("  - \"暗影领主怎么打？\"（纯攻略查询）");
            System.out.println("  - \"1200攻击打80000血的BOSS要几刀？\"（纯计算）");
            System.out.println("  - \"我需要30个暗影精华，要刷几次暗影领主？\"（先查掉率、再计算）");
            System.out.println("  - \"做3件暗系装备需要多少龙鳞碎片？\"（先查配方、再计算）");
        }
        System.out.println();
    }

    /**
     * 打印程序 Banner。
     */
    private static void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║          🎮 游戏攻略智能问答助手              ║");
        System.out.println("║          Game Strategy Q&A Assistant         ║");
        System.out.println("║          基于 RAG + 通义千问 qwen-plus       ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * 格式化向量的前 N 个分量，便于控制台展示验证结果。
     */
    private static String formatVector(float[] vector, int limit) {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(limit, vector.length);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.6f", vector[i]));
        }
        if (limit < vector.length) {
            sb.append(", ...");
        }
        sb.append("]");
        return sb.toString();
    }
}
