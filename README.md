# 🎮 游戏攻略智能问答助手

> 基于 **RAG（检索增强生成）+ 通义千问 qwen-plus** 的游戏攻略智能问答系统。
> 支持自然语言提问，从本地攻略文档中检索相关内容，生成带来源标注的回答。
> 在此基础上叠加 **Agent（智能体）层**，让 AI 自主判断"该查攻略还是该算数"。

---

## 一、环境要求

| 项目 | 要求 | 说明 |
|------|------|------|
| JDK | **21 或更高**（推荐 JDK 21 LTS） | LangChain4j 要求 Java 17+ |
| 构建工具 | Maven 3.6+ | IntelliJ IDEA 自带 Maven，无需额外安装 |
| IDE | IntelliJ IDEA（推荐） | 社区版即可，也可用 VS Code + Java 扩展 |
| 网络 | **必须联网** | 大模型和向量化都是远程 API 调用 |
| 百炼账号 | 需要阿里云百炼 API Key | 见下方"获取 API Key" |

---

## 二、获取阿里云百炼 API Key

1. 打开 [阿里云百炼 Model Studio](https://bailian.console.aliyun.com/) 控制台。
2. 登录阿里云账号，首次使用需要开通服务。
3. 左侧菜单 → **模型服务 → API Key 管理** → **创建 API Key**。
4. 创建后会显示 `sk-xxxxxxxxxxxxxxxxxxxxxxxx` 格式的 Key，**立即复制保存**（关闭后无法再次查看）。
5. ⚠️ **确认 API Key 所属地域**（创建时可选北京/新加坡/美国），程序中 baseUrl 必须和地域匹配。

---

## 三、设置环境变量

### Windows 设置方法

**方法一：永久设置（推荐）**

1. 右键"此电脑" → **属性** → **高级系统设置** → **环境变量**
2. 在"用户变量"中点击 **新建**：
   - 变量名：`DASHSCOPE_API_KEY`
   - 变量值：你的 API Key（`sk-xxx...`）
3. 确定保存。
4. ⚠️ **必须重启 IDE 和终端**才能生效！

**方法二：临时设置（仅当前终端有效）**

PowerShell：
```powershell
$env:DASHSCOPE_API_KEY="sk-你的APIKey"
```

CMD：
```cmd
set DASHSCOPE_API_KEY=sk-你的APIKey
```

### API Key 地域与 baseUrl 对应关系

| API Key 所属地域 | baseUrl | 代码中的常量 |
|---|---|---|
| 北京（华北2） | `https://dashscope.aliyuncs.com/compatible-mode/v1` | 默认 |
| 新加坡 | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` | 需修改 `ModelConfig.BASE_URL` |
| 美国弗吉尼亚 | `https://dashscope-us.aliyuncs.com/compatible-mode/v1` | 需修改 `ModelConfig.BASE_URL` |

如果程序报"鉴权失败/401错误"，请检查地域是否匹配。修改方法：编辑 `src/main/java/com/game/rag/config/ModelConfig.java` 中的 `BASE_URL` 常量。

---

## 四、放入攻略文件

1. 将你的游戏攻略文件（`.txt` 或 `.pdf` 格式）放入项目根目录下的 **`documents/`** 文件夹。
2. 项目已包含一份示例攻略 `sample_strategy.txt`（《幻境传说》Boss 攻略），可以直接用来测试。
3. 支持多个文件，程序会加载 `documents/` 目录下的所有 `.txt` 和 `.pdf` 文件。

---

## 五、编译与运行

### 在 IntelliJ IDEA 中运行

1. 用 IntelliJ 打开项目根目录 `game-strategy-rag/`。
2. IntelliJ 会自动识别 Maven 项目并下载依赖（首次可能需要几分钟）。
3. 找到 `src/main/java/com/game/rag/Main.java`。
4. 右键 → **Run 'Main.main()'**。
5. 如需切换阶段，编辑运行配置，在 Program Arguments 中填入 `phase1` 或 `phase2`。

### 用 Maven 命令行运行

> ⚠️ **Windows 控制台中文乱码解决方案（必读）**
> 
> Windows 终端默认使用 GBK（代码页 936），而程序所有输出都是 UTF-8，因此中文会变成乱码（"鈺斺晲"）。解决方法（三管齐下）：
> 
> **①（前置）终端切换代码页**：运行前在 PowerShell/CMD 中执行：
> ```powershell
> chcp 65001
> ```
> 这会告诉终端"接下来收到的字节流是 UTF-8"。
> 
> **②（JVM 层）JDK 18+ 控制台编码**：项目 `pom.xml` 和 `.vscode/launch.json` 已配置：
> - `-Dfile.encoding=UTF-8`（文件 I/O 默认编码）
> - `-Dstdout.encoding=UTF-8`（System.out 控制台编码——JDK 18+ 单独控制）
> - `-Dstderr.encoding=UTF-8`（System.err 控制台编码）
> 
> **③（Java 代码层）直接重定向文件描述符**：`Main.java` 在最开头用 `new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8")` 重建 stdout/stderr，绕过 JVM 启动时的旧编码器。
> 
> 如果仍然乱码，检查 VS Code 集成终端字体是否支持中文（设置 → `terminal.integrated.fontFamily` → 选 "Microsoft YaHei Mono" 或 "Consolas,Microsoft YaHei Mono"）。

```bash
# 进入项目目录
cd game-strategy-rag

# 【Windows 用户】先切换到 UTF-8 代码页
chcp 65001

# 编译项目
mvn clean compile

# === 阶段 0：验证 API 连通性 ===
mvn exec:java

# === 阶段 1：RAG 问答模式 ===
mvn exec:java -Dexec.args="phase1"

# === 阶段 2：Agent 智能问答模式 ===
mvn exec:java -Dexec.args="phase2"
```

---

## 六、各阶段演示说明

### 阶段 0 — API 连通性验证

**运行：** `mvn exec:java`

**预期输出：**
```
>>> 测试 1：大模型对话 (qwen-plus)
📝 大模型回复：我是通义千问，阿里巴巴推出的大语言模型。
✅ 大模型 API 测试通过！

>>> 测试 2：向量化 (text-embedding-v4)
📐 向量维度：1024
📐 向量前 5 个分量：[0.023456, -0.012345, ...]
✅ 向量化 API 测试通过！
```

**验收标准：** 控制台打印出大模型回复，以及 1024 维向量，证明两个 API 都接通。

---

### 阶段 1 — 核心 RAG 问答

**运行：** `mvn exec:java -Dexec.args="phase1"`

**预期演示流程：**

1. 程序启动，自动构建知识库（读取 `documents/` → 切块 → 向量化 → 入库）
2. 进入问答循环，输入问题：

```
🎮 请输入你的问题：
> 暗影领主怎么打？

📝 回答：
暗影领主的打法要点如下：
1. 推荐阵容：圣骑士（带圣光盾）+ 大祭司（圣光雨Lv.10）+ 光明法师 + 剑圣
2. 开局Boss会施放暗影诅咒，需要用净化术驱散
...

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📖 参考来源：
  [1] 文件：sample_strategy.txt（相关度：0.85）
     """暗影领主·墨菲斯托..."""
```

3. 测试拒答能力：

```
> 这个游戏怎么充值？

📝 回答：
攻略中未找到相关信息。
```

**验收标准：**
- 提问攻略内的内容 → 正确回答 + 显示来源文件和命中片段
- 提问攻略外的内容 → 回复"未找到相关信息"而不编造

---

### 阶段 2 — Agent 智能问答

**运行：** `mvn exec:java -Dexec.args="phase2"`

**预期演示流程：**

1. 纯攻略查询：`暗影领主怎么打？` → Agent 判断 → 调用"查攻略工具" → 给出回答
2. 纯计算题：`1200攻击打80000血的BOSS要几刀？` → Agent 判断 → 调用"计算工具" → 给出计算结果
3. 复合问题：`我需要30个暗影精华，要刷几次暗影领主？` → Agent 判断 → 先调"查攻略工具"获取掉率 → 再调"计算工具"算次数 → 给出综合结论

**验收标准：**
- Agent 能自主判断问题类型并选择正确工具
- 复合问题能"先查后算"得出综合结论
- 控制台日志能看出工具调用过程（方便答辩讲解）

---

### 阶段 3 — Web 界面（可选）

**运行：** `mvn exec:java -Dexec.args="web"`

打开浏览器访问 `http://localhost:8080`，在网页中输入问题、查看回答和来源。

---

## 七、额度与联网注意事项

⚠️ **以下事项非常重要，务必在演示前检查：**

### 消耗额度的操作
| 操作 | 何时发生 | 消耗量 |
|------|----------|--------|
| 大模型回答（qwen-plus） | 每次提问 | 按 token 计费（约几毛钱/次） |
| 向量化（text-embedding-v4） | 建库时（每块调用一次）+ 每次检索（调用一次） | 按 token 计费 |

### 演示前检查清单
- [ ] 网络正常，能访问 `dashscope.aliyuncs.com`
- [ ] 环境变量 `DASHSCOPE_API_KEY` 已设置且未过期
- [ ] API Key 地域与代码中 `BASE_URL` 一致
- [ ] 百炼账户有可用额度（至少几块钱够演示）
- [ ] `documents/` 里有攻略文件
- [ ] 程序能正常启动并完成知识库构建

### 节省额度的技巧
- 测试阶段只用一份较小的攻略文档（如项目的 `sample_strategy.txt`）
- 切块大小（`CHUNK_SIZE`）调大一点（如 800），减少向量化调用次数
- 演示前先跑一遍确保能用，避免现场翻车

---

## 八、项目结构

```
game-strategy-rag/
├── pom.xml                           # Maven 配置
├── README.md                         # 本文档
├── 原理说明.md                        # 答辩原理讲解素材
├── .gitignore                        # Git 忽略规则
├── documents/                        # 攻略文档目录
│   └── sample_strategy.txt           # 示例攻略
└── src/main/
    ├── java/com/game/rag/
    │   ├── Main.java                 # 程序入口
    │   ├── config/
    │   │   └── ModelConfig.java      # 模型初始化配置
    │   ├── rag/
    │   │   ├── KnowledgeBase.java    # 知识库（读/切/向量化/检索）
    │   │   ├── RagService.java       # RAG 调度（检索+生成+来源）
    │   │   ├── RagAnswer.java        # 回答对象（含来源）
    │   │   └── SearchResult.java     # 检索结果
    │   └── agent/
    │       ├── GameAssistantAgent.java  # Agent 定义
    │       └── tools/
    │           ├── StrategyTool.java    # 查攻略工具
    │           └── CalculatorTool.java  # 计算工具
    └── resources/
        └── logback.xml               # 日志配置
```

---

## 九、常见问题

**Q：运行报 "Unsupported class file major version 61"？**
A：JDK 版本太低，需要 JDK 17+。推荐安装 JDK 21 LTS。

**Q：运行报 401 鉴权错误？**
A：检查 ① API Key 是否正确 ② API Key 地域是否和 baseUrl 匹配 ③ 环境变量是否在重启 IDE 后生效。

**Q：向量化失败，提示 429 错误？**
A：请求过于频繁被限流。程序已配置自动重试（最多 3 次），通常在等待后会恢复。如果持续失败，请在百炼控制台检查是否欠费。

**Q：PDF 文件读不出来？**
A：确认 PDF 是文字型 PDF（不是扫描图片），项目使用 Apache PDFBox 解析，无法处理图片型 PDF。

**Q：回答不够准确？**
A：可以调整切块大小（`KnowledgeBase.CHUNK_SIZE`）和检索数量（`KnowledgeBase.DEFAULT_TOP_K`），增大 topK 可以让更多相关片段进入上下文。

**Q：中文输入输出变成乱码（"??"、"鈺斺晲"等）？**
A：这是 Windows 控制台默认使用 GBK 编码（代码页 936）与程序 UTF-8 输出冲突。
**三管齐下修复**（三重防护，缺一不可）：
1. **启动 chcp 65001**：程序运行前在终端执行 `chcp 65001`，把终端代码页切换到 UTF-8。
2. **JVM 启动参数**：JDK 18+ 中 `System.out`/`System.err` 的编码由 `stdout.encoding` / `stderr.encoding` 单独控制，**不再跟随 `file.encoding`**。所以必须同时设置这三个：
   ```
   -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8
   ```
   （`pom.xml` exec plugin 和 `.vscode/launch.json` 均已配置）
3. **Java 代码**：`Main.java` 启动时用 `new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8)` 直接重定向文件描述符，绕过 JVM 的旧编码器。
4. **字体**：如果字符显示为方块，VS Code 终端字体可能不支持中文 → 设置 `terminal.integrated.fontFamily` 为 `"Microsoft YaHei Mono"` 或其他含中文的等宽字体。
