package com.game.rag.agent;

import com.game.rag.agent.tools.CalculatorTool;
import com.game.rag.agent.tools.StrategyTool;
import com.game.rag.rag.KnowledgeBase;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Agent（智能体）定义：让大模型自主决定调用哪个工具来回答问题。
 *
 * 这是"Agent 模式"的核心。
 *
 * 与普通 RAG 的区别：
 * ┌────────────┬──────────────────────┬──────────────────────────┐
 * │            │  普通 RAG            │   Agent（智能体）         │
 * ├────────────┼──────────────────────┼──────────────────────────┤
 * │ 决策方式   │ 写死的流程           │ 模型自主判断             │
 * │            │ 每次必先检索再生成   │ 可能检索、可能计算       │
 * │            │                      │ 可能先检索再计算         │
 * ├────────────┼──────────────────────┼──────────────────────────┤
 * │ 灵活性     │ 低，只会查资料       │ 高，会按需选工具         │
 * ├────────────┼──────────────────────┼──────────────────────────┤
 * │ 适用场景   │ 纯知识问答           │ 需要多种能力的复合问题   │
 * └────────────┴──────────────────────┴──────────────────────────┘
 *
 * 实现原理（基于 LangChain4j AiServices）：
 * 1. 定义接口（本接口），描述 Agent 的行为：接收用户消息，返回回答。
 * 2. 用 @SystemMessage 告诉模型它的角色和规则。
 * 3. 通过 AiServices.builder() 把工具（@Tool 标注的方法）注册进去。
 * 4. 当用户提问时，框架自动：
 *    a. 把用户消息 + 可用工具列表发给大模型
 *    b. 大模型返回"我想调用工具 X，参数是 Y"
 *    c. 框架执行工具 X，把结果返回给大模型
 *    d. 大模型基于工具结果生成最终回答
 *    e. 如果大模型觉得还需要更多信息，会再调工具（循环直到大模型满意）
 *
 * 这就是 OpenAI 的 "function calling"（函数调用）机制，
 * 通义千问 qwen-plus 完全支持。
 */
public class GameAssistantAgent {

    /** Agent 的系统提示词 */
    private static final String SYSTEM_PROMPT = """
            你是一个游戏攻略智能助手，名字叫"攻略小帮手"。
            你有以下能力：
            1. 查阅游戏攻略资料（打法、阵容、掉落、地点等）
            2. 进行游戏相关的数学计算（算次数、算概率、算伤害等）

            工作规则：
            - 先分析用户的问题属于哪一类：纯攻略查询、纯计算、还是需要先查攻略再计算
            - 纯攻略问题 → 调用"查询游戏攻略"工具
            - 纯计算问题 → 调用"游戏计算器"工具
            - 复合问题（如"刷够XX材料要几次"）→ 先查攻略获取掉率/数量，再调用计算器算结果
            - 如果攻略中找不到相关信息，如实告诉用户，不要编造
            - 回答要简洁清晰，给出具体结论
            """;

    /**
     * Agent 的对话接口。
     * LangChain4j 会在运行时自动生成这个接口的实现类，
     * 把工具调用、结果回传、多轮对话等复杂逻辑全部封装好。
     *
     * @SystemMessage：设置系统提示词，定义 Agent 的角色和行为规则。
     * @UserMessage：标注用户输入参数。
     */
    public interface Assistant {
        /**
         * 处理用户消息，返回 Agent 的最终回答。
         * Agent 可能会在内部多次调用工具（对用户透明），
         * 最终返回的是基于工具结果生成的综合回答。
         *
         * @param userMessage 用户消息
         * @return Agent 的最终回答
         */
        @SystemMessage(SYSTEM_PROMPT)
        String chat(@UserMessage String userMessage);
    }

    /**
     * 创建 Agent 实例。
     * 注册 StrategyTool（查攻略）和 CalculatorTool（计算器）两个工具，
     * 并设置对话记忆，让 Agent 能记住之前的对话。
     *
     * @param knowledgeBase 已构建的知识库
     * @return Agent 对话接口实例
     */
    public static Assistant create(KnowledgeBase knowledgeBase) {
        // 实例化工具
        StrategyTool strategyTool = new StrategyTool(knowledgeBase);
        CalculatorTool calculatorTool = new CalculatorTool();

        // 用 AiServices 创建 Agent
        return AiServices.builder(Assistant.class)
                .chatModel(com.game.rag.config.ModelConfig.getChatModel())
                .tools(strategyTool, calculatorTool)        // 注册工具：Agent 可以调用这些
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))  // 记住最近10条对话
                .build();
    }
}
