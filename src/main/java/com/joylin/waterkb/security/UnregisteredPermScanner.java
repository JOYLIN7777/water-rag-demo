package com.joylin.waterkb.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫描"代码里已经在用、但权限树里还没建"的权限码。
 *
 * <p>用户在新增权限弹窗里只能选这些码：选完立刻能控制前端按钮/后端接口，
 * 不会出现建了一个漂亮但没人用的权限码。来源两类：
 * ① 静态页与脚本里的 data-perm="xxx"；② Controller 上的 @RequirePermission("xxx")。</p>
 */
@Service
public class UnregisteredPermScanner {

    private static final Logger log = LoggerFactory.getLogger(UnregisteredPermScanner.class);
    private static final Pattern HTML_PERM = Pattern.compile("data-perm=\"([a-z][a-z0-9_.:-]*)\"");
    private static final Pattern JS_PERM = Pattern.compile("data-perm:\\s*['\"]([a-z][a-z0-9_.:-]*)['\"]");
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_.-]*:[a-z][a-z0-9_.-]*$");

    private final JdbcTemplate jdbc;
    private final org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mapping;

    public UnregisteredPermScanner(JdbcTemplate jdbc,
                                   org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mapping) {
        this.jdbc = jdbc;
        this.mapping = mapping;
    }

    /** code → 来源描述（多个来源合并） */
    public List<Map<String, Object>> scan() {
        Map<String, List<String>> sources = new LinkedHashMap<>();
        scanResources("classpath*:static/**/*.html", HTML_PERM, sources);
        scanResources("classpath*:static/assets/*.js", JS_PERM, sources);
        scanAnnotations(sources);

        Set<String> registered;
        try {
            registered = new TreeSet<>(jdbc.queryForList(
                    "SELECT code FROM perm_node WHERE COALESCE(retired,FALSE)=FALSE", String.class));
        } catch (Exception e) {
            log.warn("已注册权限码读取失败，跳过扫描：{}", e.getMessage());
            return List.of();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        sources.forEach((code, where) -> {
            if (!CODE.matcher(code).matches() || registered.contains(code)) return;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("name", suggestName(code));
            row.put("sources", where.stream().distinct().limit(4).toList());
            out.add(row);
        });
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("code"))));
        return out;
    }

    private void scanResources(String pattern, Pattern re, Map<String, List<String>> out) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            for (Resource r : resources) {
                String file = r.getFilename() == null ? r.getDescription() : r.getFilename();
                try (InputStream in = r.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    int lineNo = 0;
                    while ((line = reader.readLine()) != null) {
                        lineNo++;
                        Matcher m = re.matcher(line);
                        while (m.find()) {
                            String code = m.group(1);
                            out.computeIfAbsent(code, k -> new ArrayList<>()).add(file + ":" + lineNo);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("静态资源权限扫描失败 pattern={}: {}", pattern, e.getMessage());
        }
    }

    /** 通过反射读 Controller 上的 @RequirePermission，只收真正会拦截请求的权限码 */
    private void scanAnnotations(Map<String, List<String>> out) {
        try {
            mapping.getHandlerMethods().forEach((info, handler) -> {
                Class<?> clazz = handler.getBeanType();
                String name = clazz.getSimpleName();
                java.lang.reflect.Method method = handler.getMethod();
                RequirePermission classAnno = clazz.getAnnotation(RequirePermission.class);
                if (classAnno != null) {
                    for (String code : classAnno.value())
                        out.computeIfAbsent(code, k -> new ArrayList<>()).add(name + "（类级）");
                }
                RequirePermission anno = method.getAnnotation(RequirePermission.class);
                if (anno != null) {
                    for (String code : anno.value())
                        out.computeIfAbsent(code, k -> new ArrayList<>()).add(name + "." + method.getName() + "()");
                }
            });
        } catch (Exception e) {
            log.warn("注解权限扫描失败：{}", e.getMessage());
        }
    }

    /** 从权限码猜一个中文名，用户可改 */
    private String suggestName(String code) {
        String action = code.substring(code.indexOf(':') + 1);
        Map<String, String> cn = Map.ofEntries(
                Map.entry("select", "查询"), Map.entry("create", "新增"), Map.entry("edit", "编辑"),
                Map.entry("delete", "删除"), Map.entry("write", "维护"), Map.entry("read", "查看"),
                Map.entry("approve", "审批"), Map.entry("review", "复核"), Map.entry("report", "上报"),
                Map.entry("issue", "下达"), Map.entry("archive", "归档"), Map.entry("stats", "统计"),
                Map.entry("print", "打印"), Map.entry("download", "下载"), Map.entry("export", "导出"),
                Map.entry("import", "导入"), Map.entry("audit", "审核"), Map.entry("config", "配置"),
                Map.entry("use", "使用"), Map.entry("docs", "文档管理"), Map.entry("access", "进入"));
        return cn.getOrDefault(action, action);
    }
}
