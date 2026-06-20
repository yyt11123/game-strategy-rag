package com.game.rag.rag;

import com.game.rag.config.ModelConfig;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 知识库：负责攻略文档的读取、切块、向量化、存入向量库，以及后续的语义检索。
 *
 * 这是 RAG（检索增强生成）的核心基础设施。简单说就是：
 * ① 把攻略文件读进来 → ② 切成小段 → ③ 每段用 AI 转成向量（一串数字）
 * → ④ 存进向量库 → ⑤ 用户提问时，把问题也转成向量，在库里找最像的几段。
 *
 * 为什么要切成小段（chunk）？
 * - 大模型一次能处理的文字量（上下文窗口）有限；
 * - 太长的段落会包含太多无关内容，干扰检索精度；
 * - 太短又可能丢失上下文。默认 500 字/块、重叠 100 字是一个常用平衡点。
 *
 * 为什么用向量做检索而不是直接关键词匹配？
 * - 用户问"打Boss用什么阵容"和攻略里的"推荐阵容"字面不同但语义一样；
 * - 向量化（Embedding）把文字映射到高维数学空间，语义相近的向量距离也近；
 * - 这就实现了"按意思搜索"，而不只是"按关键字搜索"。
 */
public class KnowledgeBase {

    /** 攻略文档存放目录 */
    private static final String DOCUMENTS_DIR = "documents";

    /** 切块大小（字符数），可调整以优化检索效果 */
    public static final int CHUNK_SIZE = 500;

    /** 相邻块的重叠字符数，避免关键信息正好被切断在边界上 */
    public static final int CHUNK_OVERLAP = 100;

    /** 检索时返回的最相关片段数量 */
    public static final int DEFAULT_TOP_K = 4;

    /** 检索相关性阈值：低于此分数的片段视为不相关，用于判断"未找到相关信息" */
    public static final double RELEVANCE_THRESHOLD = 0.25;

    // ==================== 内部状态 ====================

    /** 内存向量库：所有攻略片段的向量都存在这里，程序重启后需要重建 */
    private final InMemoryEmbeddingStore<TextSegment> embeddingStore;

    /** 向量化模型（text-embedding-v4，远程 API） */
    private final EmbeddingModel embeddingModel;

    /** 切块器：把文档按指定大小切成小块 */
    private final DocumentSplitter splitter;

    /** 知识库是否已构建完成 */
    private boolean built = false;

    /**
     * 初始化知识库。
     * 注意：这里只是创建实例，不会立即构建知识库。
     * 需要调用 build() 来实际加载文档、向量化和入库。
     */
    public KnowledgeBase() {
        this.embeddingModel = ModelConfig.getEmbeddingModel();
        this.embeddingStore = new InMemoryEmbeddingStore<>();

        // 创建切块器：按最大500字符递归切分，相邻块重叠100字符
        this.splitter = DocumentSplitters.recursive(
                CHUNK_SIZE,
                CHUNK_OVERLAP
        );
    }

    /**
     * 构建知识库：扫描 documents/ 目录下的所有 txt 和 pdf 文件，
     * 依次执行"读取 → 切块 → 向量化 → 入库"。
     *
     * ⚠️ 这会调用远程 Embedding API，消耗额度且需要联网。
     * ⚠️ 只应在程序启动时执行一次，不要在每次提问时重复调用。
     *
     * @return 入库的文本片段数量
     */
    public int build() throws Exception {
        System.out.println("\n📚 开始构建知识库...");
        System.out.println("   文档目录：" + Paths.get(DOCUMENTS_DIR).toAbsolutePath());

        Path docsPath = Paths.get(DOCUMENTS_DIR);
        if (!Files.exists(docsPath)) {
            System.out.println("⚠️ 文档目录不存在，将创建空目录。");
            Files.createDirectories(docsPath);
            System.out.println("   请将游戏攻略文件（.txt 或 .pdf）放入该目录后重启程序。");
            built = true;
            return 0;
        }

        // --- 第①步：加载所有攻略文档 ---
        List<Document> documents = loadAllDocuments(docsPath);
        if (documents.isEmpty()) {
            System.out.println("⚠️ 未在 documents/ 中找到任何 .txt 或 .pdf 文件。");
            System.out.println("   请放入攻略文件后重启程序。");
            built = true;
            return 0;
        }
        System.out.println("   ① 共加载 " + documents.size() + " 个文档文件");

        // --- 第②步：切块 ---
        List<TextSegment> allSegments = new ArrayList<>();
        for (Document doc : documents) {
            List<TextSegment> segments = splitter.split(doc);
            // 把来源文件名写入每个片段的元数据中，后续回答时可以引用
            String fileName = doc.metadata().getString("file_name");
            if (fileName != null) {
                for (TextSegment seg : segments) {
                    seg.metadata().put("file_name", fileName);
                }
            }
            allSegments.addAll(segments);
        }
        System.out.println("   ② 共切分为 " + allSegments.size() + " 个文本块");

        // --- 第③步 + 第④步：向量化 → 入库 ---
        // 逐个向量化并存入（这里也可以用 embedAll 批量处理，但逐条更稳定、好跟踪进度）
        int successCount = 0;
        int failCount = 0;
        for (int i = 0; i < allSegments.size(); i++) {
            TextSegment seg = allSegments.get(i);
            try {
                Embedding embedding = embeddingModel.embed(seg).content();
                embeddingStore.add(embedding, seg);
                successCount++;
                // 每 10 条打印一次进度，让用户知道在干活
                if ((i + 1) % 10 == 0 || (i + 1) == allSegments.size()) {
                    System.out.printf("   向量化进度：%d/%d\n", i + 1, allSegments.size());
                }
            } catch (Exception e) {
                failCount++;
                System.err.printf("   ⚠️ 第 %d 块向量化失败：%s\n", i + 1, e.getMessage());
                // LangChain4j 配置了 maxRetries，这里可能是重试耗尽后的最终失败
                if (failCount > 3) {
                    System.err.println("   ⚠️ 连续失败过多，可能额度不足或网络异常，继续处理剩余...");
                }
            }
        }

        built = true;
        System.out.printf("   ③④ 向量化 + 入库完成（成功 %d 条，失败 %d 条）\n", successCount, failCount);
        System.out.println("✅ 知识库构建完成！共 " + successCount + " 条记录可供检索。\n");
        return successCount;
    }

    /**
     * 语义检索：将用户问题向量化，在知识库中找到最相关的 topK 个片段。
     *
     * 原理：文字变成向量后，语义相近的文字在向量空间中距离也近。
     *       比如"打 Boss 用什么阵容"和"推荐阵容"的向量会很接近，
     *       即使字面不完全匹配也能找出来——这就是"语义搜索"。
     *
     * @param question 用户问题
     * @param topK     返回的最相关片段数
     * @return 检索结果列表，按相关度降序排列
     */
    public List<SearchResult> search(String question, int topK) {
        if (!built || embeddingStore == null) {
            return List.of();
        }

        // 把问题也向量化（和建库用的同一个模型，保证向量空间一致）
        Embedding questionEmbedding = embeddingModel.embed(question).content();

        // 在向量库中搜索最相似的 topK 个片段
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(questionEmbedding)
                        .maxResults(topK)
                        .minScore(RELEVANCE_THRESHOLD)
                        .build()
        );

        // 将搜索结果转为自定义的 SearchResult 对象，方便后续使用
        return result.matches().stream()
                .map(match -> new SearchResult(
                        match.embedded().text(),                              // 命中的文字
                        match.score(),                                        // 相关性分数
                        match.embedded().metadata().getString("file_name")    // 来源文件名
                ))
                .toList();
    }

    /**
     * 便捷方法：用默认 topK 检索。
     */
    public List<SearchResult> search(String question) {
        return search(question, DEFAULT_TOP_K);
    }

    /**
     * 检查知识库是否为空（没有任何文档入库成功）。
     */
    public boolean isEmpty() {
        return !built || embeddingStore == null;
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 从指定目录加载所有 txt 和 pdf 文件。
     * 手动遍历目录而不是用 FileSystemDocumentLoader，
     * 这样更直观、好调试，也能把文件名准确地写进元数据。
     *
     * @param docsPath 文档目录路径
     * @return Document 列表
     */
    private List<Document> loadAllDocuments(Path docsPath) {
        List<Document> allDocs = new ArrayList<>();

        // 遍历目录下所有文件（不递归，只读一级）
        try (Stream<Path> files = Files.list(docsPath)) {
            files.forEach(file -> {
                String fileName = file.getFileName().toString().toLowerCase();
                try {
                    if (fileName.endsWith(".txt")) {
                        // 读取 txt 文件
                        String content = Files.readString(file);
                        Document doc = Document.from(content);
                        doc.metadata().put("file_name", file.getFileName().toString());
                        allDocs.add(doc);
                        System.out.println("      📄 加载 txt：" + file.getFileName());

                    } else if (fileName.endsWith(".pdf")) {
                        // 读取 PDF 文件（用 Apache PdfBox）
                        ApachePdfBoxDocumentParser parser = new ApachePdfBoxDocumentParser();
                        Document doc = parser.parse(new FileInputStream(file.toFile()));
                        doc.metadata().put("file_name", file.getFileName().toString());
                        allDocs.add(doc);
                        System.out.println("      📄 加载 PDF：" + file.getFileName());
                    }
                } catch (IOException e) {
                    System.out.println("      ⚠️ 读取文件失败：" + file.getFileName()
                            + "，原因：" + e.getMessage());
                }
            });
        } catch (IOException e) {
            System.err.println("❌ 读取文档目录失败：" + e.getMessage());
        }

        return allDocs;
    }
}
