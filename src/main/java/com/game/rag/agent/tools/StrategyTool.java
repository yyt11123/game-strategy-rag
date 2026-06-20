package com.game.rag.agent.tools;

import com.game.rag.rag.KnowledgeBase;
import com.game.rag.rag.SearchResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;
import java.util.StringJoiner;

/**
 * 查攻略工具：把 RAG 检索封装成一个 Tool，让 Agent 可以自主决定"要不要查攻略"。
 *
 * 在普通 RAG 模式下，用户提问一定走检索流程——这是硬编码的。
 * 但在 Agent 模式下，大模型会先分析用户问题，自己判断：
 * - "这个问题攻略里可能有答案" → 调用本工具（查攻略）
 * - "这是个纯计算问题" → 调用 CalculatorTool（计算）
 * - "既要查攻略又要算" → 先调本工具，再调 CalculatorTool
 *
 * 这种"把选择权交给模型"的方式，就是 Agent（智能体）与普通 RAG 的本质区别：
 * Agent 不是被动执行固定流程，而是主动推理该用什么工具。
 */
public class StrategyTool {

    /** 知识库引用 */
    private final KnowledgeBase knowledgeBase;

    public StrategyTool(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    /**
     * 查询游戏攻略资料。
     * 当用户问的是游戏相关内容（BOSS打法、阵容推荐、材料掉落、装备信息等）时，
     * 调用此工具从攻略知识库中搜索相关的内容。
     *
     * @param query 用户想查询的游戏问题，最好提炼成关键词形式。例如"暗影领主怎么打""星光草在哪刷"
     * @return 从攻略中检索到的相关内容（包含来源文件名）
     */
    @Tool("查询游戏攻略资料库，获取BOSS打法、阵容推荐、材料掉落、刷取地点等游戏攻略信息")
    public String searchStrategy(
            @P("要查询的游戏问题或关键词，如'暗影领主推荐阵容'、'星光草刷取地点'")
            String query
    ) {
        // 在知识库中检索相关片段
        List<SearchResult> results = knowledgeBase.search(query, 4);

        if (results.isEmpty()) {
            return "攻略知识库中未找到与「" + query + "」相关的信息。";
        }

        // 将检索结果格式化为结构化文本返回给 Agent
        StringJoiner joiner = new StringJoiner("\n---\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            joiner.add("【来源 " + (i + 1) + "】"
                    + (r.fileName() != null ? " 文件：" + r.fileName() : "")
                    + "（相关度：" + String.format("%.2f", r.score()) + "）\n"
                    + r.content());
        }
        return joiner.toString();
    }
}
