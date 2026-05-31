package com.wzx.babiq.server.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 能力搜索中文别名字典。
 *
 * <p>工具 `name` / `capability_id` 必须保持 ASCII，不能为了中文搜索直接改名。
 * 这个字典只富化写入 Lucene 的辅助 searchText，让中文 query 能命中对应能力。</p>
 */
public final class CapabilityAliasDictionary {

    /** 能力名分词规则，覆盖本地工具下划线、MCP 点号和少量第三方连字符命名。 */
    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[._\\-\\s]+");

    /** token 到中文别名的静态映射；保持插入顺序，便于测试和后续人工排查索引文本。 */
    private static final Map<String, List<String>> ALIASES = aliases();

    private CapabilityAliasDictionary() {
    }

    /**
     * 根据能力名中的技术 token 富化 searchText。
     *
     * @param capabilityName 能力名或能力 id，例如 read_file、mcp.filesystem.read_text_file
     * @param originalSearchText 原始搜索文本，通常由 name、description 和来源标签拼接
     * @return 追加中文别名后的搜索文本；未知 token 会原样返回原始文本
     */
    public static String enrich(String capabilityName, String originalSearchText) {
        String original = safe(originalSearchText);
        List<String> aliases = aliasesFor(capabilityName, original);
        if (aliases.isEmpty()) {
            return original;
        }
        if (original.isBlank()) {
            return String.join(" ", aliases);
        }
        return original + " " + String.join(" ", aliases);
    }

    /**
     * 按能力名 token 收集别名，并跳过 searchText 中已经存在的词，保证重复同步不会不断膨胀。
     */
    private static List<String> aliasesFor(String capabilityName, String originalSearchText) {
        Set<String> collected = new LinkedHashSet<>();
        for (String token : TOKEN_SPLITTER.split(safe(capabilityName).toLowerCase(Locale.ROOT))) {
            List<String> tokenAliases = ALIASES.get(token);
            if (tokenAliases == null) {
                continue;
            }
            for (String alias : tokenAliases) {
                if (!originalSearchText.contains(alias)) {
                    collected.add(alias);
                }
            }
        }
        return new ArrayList<>(collected);
    }

    /**
     * 集中定义 BaBiQ 内置能力类别的中文检索词。
     */
    private static Map<String, List<String>> aliases() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("read", List.of("读取", "查看", "打开", "文件内容"));
        map.put("write", List.of("写入", "写文件", "保存", "创建", "修改文件"));
        map.put("edit", List.of("编辑", "修改", "改写"));
        map.put("exec", List.of("执行", "运行", "命令"));
        map.put("shell", List.of("终端", "命令行", "bash"));
        map.put("command", List.of("命令", "指令"));
        map.put("list", List.of("列出", "浏览", "列表"));
        map.put("dir", List.of("目录", "文件夹"));
        map.put("grep", List.of("搜索", "查找", "全文搜索"));
        map.put("search", List.of("搜索", "查找"));
        map.put("patch", List.of("补丁", "修改", "应用补丁"));
        map.put("diff", List.of("差异", "对比"));
        map.put("git", List.of("版本控制", "提交", "仓库"));
        map.put("http", List.of("请求", "网络", "调用接口"));
        map.put("fetch", List.of("拉取", "获取", "下载"));
        map.put("filesystem", List.of("文件系统", "文件"));
        map.put("plan", List.of("计划", "任务清单", "待办", "步骤", "规划"));
        map.put("todo", List.of("待办", "任务清单", "任务步骤"));
        map.put("explorer", List.of("子Agent", "子代理", "委派", "探索", "探查", "只读"));
        map.put("agent", List.of("Agent", "子Agent", "代理", "委派"));
        map.put("delegate", List.of("委派", "下放", "子任务"));
        return Map.copyOf(map);
    }

    /**
     * 把可空字符串归一化成安全文本。
     */
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
