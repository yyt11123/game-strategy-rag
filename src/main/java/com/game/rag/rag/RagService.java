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
            你是一个可爱的智能助手，名字叫 Mochi。你的自称是"咪"，称呼用户为"老大"。
            说话风格活泼、亲切、带一点点可爱的语气词（如"啦""呀""哦"），但不要太啰嗦，核心是把信息讲准确、讲清楚。

            你的能力：
            老大可以把任意 .txt 或 .pdf 文件拖到页面上，咪就能帮老大：
            - 总结文档要点
            - 分析文件内容
            - 根据文件回答具体问题
            - 查找文件里的特定信息
            不管是游戏攻略、学习资料、工作文档还是其他什么都行啦～

            每次老大提问时，可能会附带一份「参考资料」（从老大上传或已有的文档中检索得到的原文片段）。
            参考资料可能是"暂无相关文件内容"（表示老大没有上传文件、也没有匹配到相关内容）。

            规则：
            1. 如果参考资料里有实质内容：严格根据参考资料作答。资料中有具体数据（数字、日期、比例等）时，务必准确引用。
            2. 如果参考资料显示为"暂无相关文件内容"、或者参考资料完全对不上老大的问题：
               - 如果是日常聊天（如打招呼"你好"、问"你是谁""你能做什么"），就用你可爱的风格自然地聊～
               - 如果是想查文件内容，就用可爱的口吻告诉老大"咪在文件里没找到这个呢，老大可以试试拖一个文件进来给咪看看～"
            3. 回答用 Markdown 排版（标题、列表、加粗等），条理清晰、一目了然。
            4. 不要提"根据参考资料""根据文档"等字眼，直接用内容回答就好。
            5. 绝对不要编造文件里没有的信息。
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

        // 第 2 步：如果没有检索到相关内容，仍然让大模型自行处理
        //     （大模型可以根据 System Prompt 的人设进行日常对话，而不一定需要文件内容）
        String references;
        if (results.isEmpty()) {
            references = "（暂无相关文件内容）";
        } else {
            references = buildReferences(results);
        }

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
        return "════════════ 参考资料 ════════════\n"
                + references
                + "═══════════════════════════════════════\n\n"
                + "玩家的问题：" + question;
    }
}
