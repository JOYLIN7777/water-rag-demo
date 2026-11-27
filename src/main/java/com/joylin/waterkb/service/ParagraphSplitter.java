package com.joylin.waterkb.service;

import com.joylin.waterkb.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 中文段落重叠切片器。
 *
 *
 */
@Component
public class ParagraphSplitter {

    /**
     * 句子边界：中文句末标点（正向后视断行），用于超长段二次切分和重叠回退点。
     */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？；!?;\n])");

    private final RagProperties props;

    public ParagraphSplitter(RagProperties props) {
        this.props = props;
    }

    /**
     * 将全文切成带重叠窗口的片段列表。
     */
    public List<String> split(String text) {
        int maxChars = props.getChunkMaxChars();

        // 第 1 步：按空行切成自然段（markdown 有空行分段；pdf 抽取文本无空行时走第 2 步兜底）
        List<String> units = new ArrayList<>();
        for (String p : text.split("\\r?\\n\\s*\\r?\\n")) {
            String para = p.trim();
            if (para.isEmpty()) {
                continue;
            }
            // 第 2 步：单段超过 maxChars，按句子边界二次切分再合并
            if (para.length() > maxChars) {
                units.addAll(splitBySentence(para, maxChars));
            } else {
                units.add(para);
            }
        }

        // 第 3 步：贪心聚合 + 重叠窗口
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String unit : units) {
            if (cur.length() > 0 && cur.length() + unit.length() + 1 > maxChars) {
                // 当前块装不下了：封口入列
                chunks.add(cur.toString().trim());
                // 新块开头携带上一块末尾的 overlap 字符（重叠窗口）
                String carry = tailWithBoundary(cur.toString(), props.getChunkOverlap());
                cur = new StringBuilder(carry);
            }
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(unit);
        }
        if (cur.length() > 0) {
            chunks.add(cur.toString().trim());
        }
        return chunks;
    }

    /**
     * 超长段落按句子切分后贪心合并，保证每片不超过 maxChars。
     */
    private List<String> splitBySentence(String para, int maxChars) {
        String[] sentences = SENTENCE_BOUNDARY.split(para);
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String s : sentences) {
            // 兜底：单个"句子"本身超长（极端无标点文本，如表格导出），硬切
            if (s.length() > maxChars) {
                if (sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
                for (int i = 0; i < s.length(); i += maxChars) {
                    out.add(s.substring(i, Math.min(s.length(), i + maxChars)));
                }
                continue;
            }
            if (sb.length() + s.length() > maxChars && sb.length() > 0) {
                out.add(sb.toString());
                sb.setLength(0);
            }
            sb.append(s);
        }
        if (sb.length() > 0) {
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * 取末尾 overlap 字符作为下一块的开头，并尽量回退到句子边界之后，
     * 避免重叠区从半句话中间开始（半句对向量语义是噪声）。
     */
    private String tailWithBoundary(String chunk, int overlap) {
        if (overlap <= 0 || chunk.length() <= overlap) {
            return "";
        }
        String tail = chunk.substring(chunk.length() - overlap);
        // 找重叠区内最后一个句子边界
        int idx = -1;
        for (char c : new char[]{'。', '！', '？', '；', '!', '?', ';', '\n'}) {
            int i = tail.lastIndexOf(c);
            if (i > idx) {
                idx = i;
            }
        }
        // 边界之后还有内容才带过去；找不到边界就整段带上（宁多勿缺）
        String carry = (idx >= 0 && idx < tail.length() - 1) ? tail.substring(idx + 1) : tail;
        return carry.trim();
    }
}
