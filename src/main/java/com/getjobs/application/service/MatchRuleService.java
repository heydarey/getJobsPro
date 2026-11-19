package com.getjobs.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.match.MatchRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 岗位匹配规则的读写服务。规则保存在 match_rule 表（只有 id=1 一行），Boss 与智联共用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchRuleService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * 启动时建表；表为空时写入默认规则。不会覆盖已有规则。
     * 入参：无。出参：无。
     */
    @jakarta.annotation.PostConstruct
    public void ensureTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS match_rule ("
                + "id INTEGER PRIMARY KEY,"
                + "title_include TEXT,"
                + "title_exclude TEXT,"
                + "min_score INTEGER,"
                + "greeting_forbidden TEXT,"
                + "job_requirement TEXT,"
                + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM match_rule WHERE id = 1", Integer.class);
        if (count == null || count == 0) {
            save(MatchRule.defaults());
            log.info("已写入默认岗位匹配规则");
        }
    }

    /**
     * 读取当前规则。
     *
     * @return MatchRule，当前规则；表中无数据时返回默认规则，不返回 null
     */
    public MatchRule get() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM match_rule WHERE id = 1");
        if (rows.isEmpty()) {
            return MatchRule.defaults();
        }
        Map<String, Object> row = rows.get(0);
        MatchRule r = new MatchRule();
        r.setTitleIncludeKeywords(readList(row.get("title_include")));
        r.setTitleExcludeKeywords(readList(row.get("title_exclude")));
        Object score = row.get("min_score");
        r.setMinScore(score instanceof Number n ? n.intValue() : 85);
        r.setGreetingForbiddenWords(readList(row.get("greeting_forbidden")));
        Object req = row.get("job_requirement");
        r.setJobRequirement(req == null ? "" : req.toString());
        return r;
    }

    /**
     * 读取匹配规则最近修改时间（本地时间）。规则修改后，之前被过滤的岗位需要按新规则重新判定。
     * 入参：无。
     *
     * @return LocalDateTime，最近修改时间；无记录或无法解析时返回 null
     */
    public java.time.LocalDateTime getUpdatedAt() {
        try {
            String t = jdbc.queryForObject("SELECT updated_at FROM match_rule WHERE id = 1", String.class);
            return t == null ? null : java.time.LocalDateTime.parse(t.trim().replace(' ', 'T'));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 保存规则（整体覆盖）。列表项会去首尾空白、去空、去重；分数限制在 0~100。
     *
     * @param rule MatchRule，必填，要保存的规则
     * @return MatchRule，规范化后实际保存的规则
     */
    public MatchRule save(MatchRule rule) {
        MatchRule r = normalize(rule);
        try {
            jdbc.update("INSERT OR REPLACE INTO match_rule "
                            + "(id, title_include, title_exclude, min_score, greeting_forbidden, job_requirement, updated_at) "
                            + "VALUES (1, ?, ?, ?, ?, ?, ?)",
                    objectMapper.writeValueAsString(r.getTitleIncludeKeywords()),
                    objectMapper.writeValueAsString(r.getTitleExcludeKeywords()),
                    r.getMinScore(),
                    objectMapper.writeValueAsString(r.getGreetingForbiddenWords()),
                    r.getJobRequirement(),
                    java.time.LocalDateTime.now().toString());
        } catch (Exception e) {
            throw new IllegalStateException("保存岗位匹配规则失败：" + e.getMessage(), e);
        }
        return r;
    }

    /**
     * 规范化规则字段。
     *
     * @param rule MatchRule，可空；为空时按全空规则处理
     * @return MatchRule，新对象，列表非空引用，分数在 0~100
     */
    private MatchRule normalize(MatchRule rule) {
        MatchRule src = rule == null ? new MatchRule() : rule;
        MatchRule r = new MatchRule();
        r.setTitleIncludeKeywords(cleanList(src.getTitleIncludeKeywords()));
        r.setTitleExcludeKeywords(cleanList(src.getTitleExcludeKeywords()));
        int score = src.getMinScore() == null ? 85 : src.getMinScore();
        r.setMinScore(Math.max(0, Math.min(100, score)));
        r.setGreetingForbiddenWords(cleanList(src.getGreetingForbiddenWords()));
        r.setJobRequirement(src.getJobRequirement() == null ? "" : src.getJobRequirement().trim());
        return r;
    }

    /**
     * 列表去空白、去空项、去重，保持原顺序。
     *
     * @param list List&lt;String&gt;，可空
     * @return List&lt;String&gt;，新列表，不为 null
     */
    private static List<String> cleanList(List<String> list) {
        if (list == null) return new ArrayList<>();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : list) {
            if (s != null && !s.isBlank()) set.add(s.trim());
        }
        return new ArrayList<>(set);
    }

    /**
     * 把数据库中的 JSON 文本解析为字符串列表。
     *
     * @param raw Object，可空，数据库字段值
     * @return List&lt;String&gt;，解析失败或为空时返回空列表
     */
    private List<String> readList(Object raw) {
        if (raw == null || raw.toString().isBlank()) return new ArrayList<>();
        try {
            return cleanList(objectMapper.readValue(raw.toString(), STRING_LIST));
        } catch (Exception e) {
            log.warn("解析匹配规则列表失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
