package com.getjobs.application.service;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.worker.match.JobCandidate;
import com.getjobs.worker.match.MatchDecision;
import com.getjobs.worker.match.MatchRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 岗位匹配闸门：所有向 HR 发送消息 / 投递简历的动作都必须先经过这里。
 * <p>
 * 原则：只有完全匹配才放行；规则不满足、信息不完整、AI 调用失败、AI 返回无法解析、分数不够，一律不发送。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobMatchService {

    /** 岗位描述送给 AI 的最大字数，避免超长 */
    private static final int MAX_DESC_CHARS = 3000;

    private final MatchRuleService matchRuleService;
    private final AiService aiService;

    /**
     * 任务启动前的自检：规则完整、AI 可用、固定招呼语合规。任一项不通过，任务不得启动。
     *
     * @param sayHi String，可空，平台配置的固定招呼语；Boss 传入，智联传 null（智联不发招呼语）
     * @return List&lt;String&gt;，问题列表；为空表示自检通过
     */
    public List<String> preflight(String sayHi) {
        List<String> problems = new ArrayList<>();
        MatchRule rule = matchRuleService.get();
        if (rule.getTitleIncludeKeywords().isEmpty()) {
            problems.add("岗位匹配规则中“岗位名称必含词”为空，无法保证岗位方向，请先配置");
        }
        if (sayHi != null) {
            if (sayHi.isBlank()) {
                problems.add("固定招呼语为空");
            } else {
                String hit = findHit(sayHi, rule.getGreetingForbiddenWords());
                if (hit != null) {
                    problems.add("固定招呼语包含禁用词“" + hit + "”，请修改后再投递");
                }
            }
        }
        AiEntity ai = aiService.getAiConfig();
        if (ai == null || ai.getIntroduce() == null || ai.getIntroduce().isBlank()
                || ai.getIntroduce().contains("请在此填写")) {
            problems.add("AI 配置中的“技能介绍”未填写，AI 无法判断匹配度");
        }
        try {
            aiService.chatStrict("你是连通性检查。", "只回复：OK");
        } catch (Exception e) {
            problems.add("AI 不可用：" + e.getMessage());
        }
        return problems;
    }

    /**
     * 只按岗位名称规则做快速判定（不调用 AI），用于在打开详情页之前先排除明显不相关的岗位。
     *
     * @param jobName String，可空，岗位名称
     * @return MatchDecision，不通过时返回拒绝结果；名称规则通过时返回 null
     */
    public MatchDecision checkTitle(String jobName) {
        MatchRule rule = matchRuleService.get();
        String name = jobName == null ? "" : jobName.trim();
        if (name.isEmpty()) {
            return MatchDecision.reject("信息完整性", "岗位名称为空", null);
        }
        if (rule.getTitleIncludeKeywords().isEmpty() || findHit(name, rule.getTitleIncludeKeywords()) == null) {
            return MatchDecision.reject("名称必含词", "岗位名称不含任何必含词", null);
        }
        String excluded = findHit(name, rule.getTitleExcludeKeywords());
        if (excluded != null) {
            return MatchDecision.reject("名称排除词", "岗位名称包含排除词“" + excluded + "”", null);
        }
        return null;
    }

    /**
     * 判定一个岗位是否允许发送，并给出要发送的招呼语。
     *
     * @param c           JobCandidate，必填，岗位信息
     * @param sayHi       String，可空，固定招呼语；AI 招呼语不可用时的回退文本，也作为 AI 的参考
     * @param aiGreeting  boolean，必填，true 时使用 AI 根据岗位调整的招呼语，false 时使用固定招呼语
     * @return MatchDecision，判定结果，不为 null
     */
    public MatchDecision evaluate(JobCandidate c, String sayHi, boolean aiGreeting) {
        MatchRule rule = matchRuleService.get();
        String name = c.jobName() == null ? "" : c.jobName().trim();
        String desc = c.description() == null ? "" : c.description().trim();

        // 1. 信息完整性：岗位名称和描述缺一不可，否则无从判断
        if (name.isEmpty() || desc.isEmpty()) {
            return MatchDecision.reject("信息完整性", "岗位名称或岗位描述为空，无法判断", null);
        }
        // 2. 岗位名称必含词
        if (rule.getTitleIncludeKeywords().isEmpty()) {
            return MatchDecision.reject("名称必含词", "未配置岗位名称必含词", null);
        }
        if (findHit(name, rule.getTitleIncludeKeywords()) == null) {
            return MatchDecision.reject("名称必含词", "岗位名称不含任何必含词", null);
        }
        // 3. 岗位名称排除词
        String excluded = findHit(name, rule.getTitleExcludeKeywords());
        if (excluded != null) {
            return MatchDecision.reject("名称排除词", "岗位名称包含排除词“" + excluded + "”", null);
        }
        // 4. AI 严格复核
        JSONObject result;
        try {
            String raw = aiService.chatStrict(buildSystemPrompt(rule), buildUserContent(c, sayHi, rule));
            result = parseJson(raw);
        } catch (Exception e) {
            log.warn("AI 复核失败，按不发送处理 | 岗位：{} | 原因：{}", name, e.getMessage());
            return MatchDecision.reject("AI复核", MatchDecision.AI_ERROR_PREFIX + "，按规定不发送：" + e.getMessage(), null);
        }
        if (!result.has("match") || !(result.get("match") instanceof Boolean)
                || !result.has("score") || !(result.get("score") instanceof Number)) {
            return MatchDecision.reject("AI复核", MatchDecision.AI_ERROR_PREFIX + "：返回缺少 match/score 字段，按规定不发送", null);
        }
        boolean match = result.getBoolean("match");
        int score = result.getNumber("score").intValue();
        String reason = result.optString("reason", "").trim();
        if (!match) {
            return MatchDecision.reject("AI复核", "AI 判定不匹配：" + reason, score);
        }
        int minScore = rule.getMinScore() == null ? 85 : rule.getMinScore();
        if (score < minScore) {
            return MatchDecision.reject("AI复核", "匹配分 " + score + " 低于阈值 " + minScore + "：" + reason, score);
        }

        // 5. 招呼语：智联不发招呼语（sayHi 传 null 且 aiGreeting=false）
        if (sayHi == null && !aiGreeting) {
            return new MatchDecision(true, "通过", reason, score, null);
        }
        String greeting = null;
        String note = "";
        if (aiGreeting) {
            String g = result.optString("greeting", "").trim();
            String problem = checkGreeting(g, rule);
            if (problem == null) {
                greeting = g;
            } else {
                note = "（AI 招呼语" + problem + "，改用固定招呼语）";
            }
        }
        if (greeting == null) {
            String problem = checkGreeting(sayHi == null ? "" : sayHi.trim(), rule);
            if (problem != null) {
                return MatchDecision.reject("招呼语校验", "固定招呼语" + problem, score);
            }
            greeting = sayHi.trim();
        }
        return new MatchDecision(true, "通过", reason + note, score, greeting);
    }

    /**
     * 校验招呼语是否可以发送。
     *
     * @param g    String，必填，待校验的招呼语
     * @param rule MatchRule，必填，当前规则（取禁用词）
     * @return String，问题描述；null 表示可以发送
     */
    private String checkGreeting(String g, MatchRule rule) {
        if (g == null || g.isBlank()) return "为空";
        if (g.length() < 10) return "过短";
        if (g.length() > 300) return "超过 300 字";
        String lower = g.toLowerCase(Locale.ROOT);
        if (g.contains("{") || g.contains("}") || lower.contains("\"match\"") || lower.contains("score")) {
            return "包含程序格式内容";
        }
        String hit = findHit(g, rule.getGreetingForbiddenWords());
        if (hit != null) return "包含禁用词“" + hit + "”";
        return null;
    }

    /**
     * 构造 AI 系统提示词。
     *
     * @param rule MatchRule，必填，当前规则
     * @return String，系统提示词
     */
    private String buildSystemPrompt(MatchRule rule) {
        String forbidden = rule.getGreetingForbiddenWords().isEmpty() ? "无"
                : String.join("、", rule.getGreetingForbiddenWords());
        return """
                你是求职者的岗位匹配审核员。向 HR 发消息是严肃的事，你的职责是把关岗位方向与工作方式：两者都与求职要求一致才放行，方向或工作方式存在不确定时判为不匹配。
                判定规则：
                1. 方向：岗位的核心工作必须是求职要求中的方向（前端开发）；岗位职责主要是其他方向（即使名称里带有相关字眼，例如实为销售、设计、测试、后端、运营）的，不匹配。
                2. 工作方式：必须符合求职要求；岗位要求驻场、坐班、到岗办公、只招全职的，不匹配；岗位明确要求工作日白天固定时段在线或打卡的，不匹配；岗位信息无法确认是否支持远程的，不匹配。平台标签“每周工作X天”“工作X个月”本身不算冲突。
                3. 能力要求：只要岗位属于前端领域，技术栈、框架、工具、工作年限、学历、证书、项目经验等要求，一律视为求职者具备，不得以“资料未体现”为由判为不匹配或扣分。
                4. 疑似培训收费、刷单、招代理、拉人头、销售推广、先交费的，不匹配。
                5. score 为 0~100 的整数，表示你对“方向与工作方式都符合”的把握程度；不得因为能力要求扣分。
                招呼语要求（match 为 true 时填写 greeting，否则 greeting 为空字符串）：
                - 只能使用求职者资料和参考招呼语中出现过的事实，不得编造或夸大身份、技能、年限、项目、可投入时间（规则 3 的“视为具备”只用于判定，不能写进招呼语）；
                - 以参考招呼语为基础，根据岗位要求调整表述重点，突出与岗位的对应点；
                - 对自己身份的称呼与参考招呼语保持一致；120 字以内；不使用表情符号、markdown、引号包裹；
                - 禁止出现这些词：%s。
                输出要求：只输出一个 JSON 对象，不要输出任何其他文字，格式为
                {"match": true 或 false, "score": 整数, "reason": "不超过 60 字的判定理由", "greeting": "招呼语"}
                """.formatted(forbidden);
    }

    /**
     * 构造 AI 用户消息。
     *
     * @param c     JobCandidate，必填，岗位信息
     * @param sayHi String，可空，参考招呼语
     * @param rule  MatchRule，必填，当前规则
     * @return String，用户消息
     */
    private String buildUserContent(JobCandidate c, String sayHi, MatchRule rule) {
        AiEntity ai = aiService.getAiConfig();
        String introduce = ai == null || ai.getIntroduce() == null ? "" : ai.getIntroduce();
        String desc = c.description();
        if (desc.length() > MAX_DESC_CHARS) desc = desc.substring(0, MAX_DESC_CHARS);
        return "【求职者资料】\n" + introduce
                + "\n\n【求职要求】\n" + rule.getJobRequirement()
                + "\n\n【搜索关键词】" + nz(c.keyword())
                + "\n\n【参考招呼语】\n" + nz(sayHi)
                + "\n\n【岗位】\n平台：" + nz(c.platform())
                + "\n名称：" + nz(c.jobName())
                + "\n公司：" + nz(c.companyName())
                + "\n薪资：" + nz(c.salary())
                + "\n地点：" + nz(c.location())
                + "\n描述：\n" + desc;
    }

    /**
     * 从 AI 返回文本中取出 JSON 对象（兼容被 ```json 包裹的情况）。
     *
     * @param raw String，必填，AI 返回文本
     * @return JSONObject，解析结果
     * @throws IllegalArgumentException 找不到或无法解析 JSON 时抛出
     */
    private JSONObject parseJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("AI 返回内容不是 JSON");
        }
        return new JSONObject(raw.substring(start, end + 1));
    }

    /**
     * 不区分大小写查找文本中命中的第一个词。
     *
     * @param text  String，可空，被查找文本
     * @param words List&lt;String&gt;，可空，词列表
     * @return String，命中的词；未命中返回 null
     */
    public static String findHit(String text, List<String> words) {
        if (text == null || words == null) return null;
        String t = text.toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (w != null && !w.isBlank() && t.contains(w.trim().toLowerCase(Locale.ROOT))) {
                return w.trim();
            }
        }
        return null;
    }

    /**
     * 空值转空串。
     *
     * @param s String，可空
     * @return String，不为 null
     */
    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
