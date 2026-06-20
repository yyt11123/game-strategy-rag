package com.game.rag.rag;

import com.game.rag.config.ModelConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;

/**
 * RAG 服务：编排"检索 → 拼提示词 → 调用大模型 → 组织带来源的回答"全流程。
 *
 * 这是 RAG 核心逻辑的"调度中心"。
 * 流程：
 * ① 拿到用户问题
 * ② 调用 KnowledgeBase.search() 检索相关攻略片段
 * ③ 将【检索到的片段 + 系统指令 + 用户问题】拼成一条完整的 Prompt
 * ④ 发给 qwen-plus 生成回答
 * ⑤ 在回答末尾附上来源信息（文件名 + 命中片段）
 *
 * 为什么要把检索到的片段放进 Prompt？
 * - 大模型本身不知道你的攻略内容（它训练数据里没有），直接问会瞎编（幻觉）；
 * - 把攻略原文"喂"给它，限制它只根据给定资料回答，就能大幅减少幻觉；
 * - 这就是 RAG（检索增强生成）的本质：先检索资料，再让模型基于资料生成。
 */
public class RagService {

    /** 知识库 —— 负责检索相关攻略片段 */
    private final KnowledgeBase knowledgeBase;

    /** 大模型 —— 负责基于检索到的资料生成通顺回答 */
    private final ChatModel chatModel;

    /** 系统提示词：告诉大模型它是什么角色、要遵守什么规则 */
    private static final String SYSTEM_PROMPT = """
            你是一个专业的游戏攻略助手，名叫"攻略小帮手"。
            玩家会在每次提问时附带一份「攻略参考资料」（从攻略知识库中检索得到的原文片段）。
            你的任务是：仔细阅读参考资料，从中提取答案来回复玩家。

            规则：
            1. 参考答案资料来回答。资料中有具体数据（伤害、血量、掉率等）时，务必准确引用。
            2. 回答要简洁有条理，尽量分点列出。
            3. 回答中不要出现"根据参考资料""根据攻略"等字眼，直接给玩家答案即可。
            4. 只有一种情况可以说"攻略中未找到相关信息"：参考资料确实完全对不上玩家的问题。
               如果资料里有相关内容——哪怕是间接相关——你也要尽力基于资料作答，不要轻易说未找到。
            """;

    /**
     * 初始化 RAG 服务。
     *
     * @param knowledgeBase 已构建好的知识库
     */
    public RagService(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
        this.chatModel = ModelConfig.getChatModel();
    }

    /**
     * RAG 问答：接收用户问题，执行完整的检索+生成流程，返回带来源的回答。
     *
     * @param question 用户的自然语言问题
     * @return 包含回答内容和来源信息的回答对象
     */
    public RagAnswer ask(String question) {
        // 第 1 步：在知识库中检索相关片段
        List<SearchResult> results = knowledgeBase.search(question);

        // 第 2 步：如果没有找到相关内容，直接返回"未找到"
        if (results.isEmpty()) {
            return new RagAnswer(
                    "攻略中未找到相关信息。知识库可能不包含这个问题的答案。",
                    List.of()
            );
        }

        // 第 3 步：将检索到的片段拼成"参考资料"
        String references = buildReferences(results);

        // 第 4 步：用 SystemMessage + UserMessage 构建消息列表
        //     关键修复：之前把系统指令和用户问题全都塞进一条
        //     chatModel.chat(String)，qwen-plus 将其视为一条
        //     普通 UserMessage，对嵌入其中的"规则"遵从度很低，
        //     导致模型倾向于直接回复"攻略中未找到相关信息"。
        //     现在拆成 SystemMessage（角色与规则）+ UserMessage
        //     （参考资料 + 玩家问题），qwen-plus 能正确区分
        //     "系统指令"和"用户数据"，从而基于参考资料作答。
        SystemMessage sysMsg = SystemMessage.from(SYSTEM_PROMPT);
        UserMessage userMsg = UserMessage.from(buildUserContent(references, question));

        // 第 5 步：调用大模型生成回答（SystemMessage + UserMessage）
        AiMessage aiMsg = chatModel.chat(sysMsg, userMsg).aiMessage();
        String answer = aiMsg.text();

        // 第 6 步：包装成带来源信息的回答对象
        return new RagAnswer(answer, results);
    }

    /**
     * 动态添加文档到知识库（供 Web 拖拽上传使用）。
     * 委托给 KnowledgeBase.addDocument()。
     *
     * @param fileName 文件名（用于来源标注）
     * @param content  文件内容：txt 为纯文本；pdf 为 base64 字符串
     * @param type     文件类型："txt" 或 "pdf"
     * @return 成功入库的文本块数量
     */
    public int addDocument(String fileName, String content, String type) throws Exception {
        return knowledgeBase.addDocument(fileName, content, type);
    }

    /**
     * 将多条检索结果拼接为编号的参考资料文本。
     * 每条前面标上序号和来源文件名，方便大模型引用，也方便用户核对。
     */
    private String buildReferences(List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append("【参考资料 ").append(i + 1).append("】");
            if (r.fileName() != null && !r.fileName().isBlank()) {
                sb.append("（来源文件：").append(r.fileName()).append("）");
            }
            sb.append("\n").append(r.content()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 构建用户消息内容：参考资料放在前面，玩家问题放在后面。
     * 注意：系统指令已通过 SystemMessage 单独发送，这里只负责传递数据。
     */
    private String buildUserContent(String references, String question) {
        return "════════════ 攻略参考资料 ════════════\n"
                + references
                + "═══════════════════════════════════════\n\n"
                + "玩家的问题：" + question;
    }
}
