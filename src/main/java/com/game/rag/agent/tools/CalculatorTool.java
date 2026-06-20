package com.game.rag.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * 计算工具：处理游戏中的数学计算问题。
 *
 * 这类问题用大模型直接算容易出错（大模型本质是"文字接龙"，不擅长精确算数），
 * 但用 Java 代码算就非常可靠。所以把它封装成一个工具（Tool），
 * 让 Agent 在需要算数时调用它，而不是自己硬算。
 *
 * 在 LangChain4j 中，工具就是普通的 Java 方法 + @Tool 注解。
 * 框架会自动把方法的 Javadoc（工具描述）和参数信息发给大模型，
 * 大模型根据描述判断"什么时候该用这个工具"、"参数怎么填"。
 *
 * 支持的运算类型：
 * - basic：基础四则运算，如 "1200攻击打8000血BOSS要几刀" → 8000/1200
 * - farm：刷材料次数计算，如 "需要30个材料每次掉3个" → 30/3 向上取整
 * - gather_total：已知每趟产量和次数，算总产量
 * - percent：百分比计算，如 "某物品5%掉率，期望多少次"
 */
public class CalculatorTool {

    /**
     * 游戏计算器：处理游戏中需要算数的问题。
     * 支持的 op（运算类型）：
     * - "basic"：基础除法。totalValue=总量，perUnit=每单位量。例如"8000血的BOSS，每刀1200伤害，需要几刀" → totalValue=8000, perUnit=1200。
     * - "farm"：刷材料次数。needed=需要的总数，perDrop=每次掉落数。结果向上取整，因为次数必须是整数。
     * - "gather_total"：计算总产量。times=次数，perTime=每次产量。
     * - "percent"：根据概率计算期望次数。targetCount=目标数量，dropRate=掉率（0~100的百分比），perDrop=每次掉落数。
     *
     * @param op          运算类型（basic / farm / gather_total / percent）
     * @param totalValue  总量（用于 basic 运算）
     * @param perUnit     每单位量（用于 basic 运算）
     * @param needed      需要的总数（用于 farm 运算）
     * @param perDrop     每次掉落数（用于 farm 和 percent 运算）
     * @param times       次数（用于 gather_total 运算）
     * @param perTime     每次产量（用于 gather_total 运算）
     * @param targetCount 目标数量（用于 percent 运算）
     * @param dropRate    掉率百分比（用于 percent 运算，0~100）
     * @return 计算结果的中文描述
     */
    @Tool("游戏计算器，用于处理算数问题，如：打BOSS需要几刀、刷材料要几次、根据掉率算期望次数等")
    public String calculate(
            @P("运算类型：basic（基础除法）/ farm（刷材料次数）/ gather_total（算总产量）/ percent（概率期望）")
            String op,

            @P("总量（用于basic运算）")
            double totalValue,

            @P("每单位量（用于basic运算）")
            double perUnit,

            @P("需要的总数（用于farm运算）")
            double needed,

            @P("每次掉落数（用于farm和percent运算）")
            double perDrop,

            @P("次数（用于gather_total运算）")
            int times,

            @P("每次产量（用于gather_total运算）")
            double perTime,

            @P("目标数量（用于percent运算）")
            int targetCount,

            @P("掉率百分比（用于percent运算，0~100的数值）")
            double dropRate
    ) {
        switch (op) {
            case "basic" -> {
                if (perUnit <= 0) return "计算错误：每单位量必须大于0";
                double result = totalValue / perUnit;
                int rounded = (int) Math.ceil(result);
                return String.format(
                        "【计算结果】基础除法\n总量 %.1f ÷ 每单位 %.1f = %.2f\n向上取整：需要 %d 次/刀",
                        totalValue, perUnit, result, rounded
                );
            }
            case "farm" -> {
                if (perDrop <= 0) return "计算错误：每次掉落数必须大于0";
                double result = needed / perDrop;
                int rounded = (int) Math.ceil(result);
                return String.format(
                        "【计算结果】刷材料次数\n需要 %.0f 个材料，每次掉落 %.1f 个\n"
                                + "需要刷 %.2f 次，向上取整 = %d 次",
                        needed, perDrop, result, rounded
                );
            }
            case "gather_total" -> {
                double result = times * perTime;
                return String.format(
                        "【计算结果】总产量\n%d 次 × 每次 %.1f 个 = %.1f 个",
                        times, perTime, result
                );
            }
            case "percent" -> {
                if (dropRate <= 0 || perDrop <= 0)
                    return "计算错误：掉率和每次掉落数必须大于0";
                // 期望次数 = 目标数量 / (掉率 × 每次掉落数)
                double rate = dropRate / 100.0;
                double expectPerRun = rate * perDrop;
                double expectRuns = targetCount / expectPerRun;
                int rounded = (int) Math.ceil(expectRuns);
                return String.format(
                        "【计算结果】概率期望\n目标：%d 个，掉率 %.1f%%，每次掉 %.1f 个\n"
                                + "每次期望获得：%.2f 个\n"
                                + "期望需要刷：%.2f 次，向上取整 ≈ %d 次",
                        targetCount, dropRate, perDrop,
                        expectPerRun, expectRuns, rounded
                );
            }
            default -> {
                return "未知运算类型：" + op + "。支持的类型：basic / farm / gather_total / percent";
            }
        }
    }
}
