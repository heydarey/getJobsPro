package com.getjobs.application.controller;

import com.getjobs.application.service.JobMatchService;
import com.getjobs.application.service.MatchRuleService;
import com.getjobs.worker.match.JobCandidate;
import com.getjobs.worker.match.MatchDecision;
import com.getjobs.worker.match.MatchRule;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 岗位匹配规则接口（Boss / 智联共用）。
 */
@RestController
@RequestMapping("/api/matchRule")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class MatchRuleController {

    private final MatchRuleService matchRuleService;
    private final JobMatchService jobMatchService;

    /**
     * 读取当前匹配规则。
     *
     * @return MatchRule，当前规则
     */
    @GetMapping
    public MatchRule get() {
        return matchRuleService.get();
    }

    /**
     * 保存匹配规则（整体覆盖）。
     *
     * @param rule MatchRule，必填，titleIncludeKeywords 不能为空
     * @return Map，success:boolean、message:String、rule:MatchRule（成功时）
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody MatchRule rule) {
        Map<String, Object> resp = new HashMap<>();
        if (rule == null || rule.getTitleIncludeKeywords() == null
                || rule.getTitleIncludeKeywords().stream().allMatch(s -> s == null || s.isBlank())) {
            resp.put("success", false);
            resp.put("message", "岗位名称必含词不能为空");
            return ResponseEntity.badRequest().body(resp);
        }
        resp.put("success", true);
        resp.put("message", "匹配规则已保存");
        resp.put("rule", matchRuleService.save(rule));
        return ResponseEntity.ok(resp);
    }

    /**
     * 用一段岗位信息试跑匹配判定，不发送任何消息。用于调整规则后核对判定口径。
     *
     * @param body Map，jobName:String 必填、description:String 必填、companyName/salary/keyword/sayHi:String 可空、
     *             aiGreeting:Boolean 可空（默认 true）
     * @return Map，pass:boolean、stage:String、reason:String、score:Integer、greeting:String
     */
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(@RequestBody Map<String, Object> body) {
        JobCandidate c = new JobCandidate("boss", str(body.get("keyword")), str(body.get("jobName")),
                str(body.get("companyName")), str(body.get("salary")), str(body.get("location")),
                str(body.get("description")));
        boolean aiGreeting = !Boolean.FALSE.equals(body.get("aiGreeting"));
        MatchDecision d = jobMatchService.evaluate(c, str(body.get("sayHi")), aiGreeting);
        Map<String, Object> resp = new HashMap<>();
        resp.put("pass", d.pass());
        resp.put("stage", d.stage());
        resp.put("reason", d.reason());
        resp.put("score", d.score());
        resp.put("greeting", d.greeting());
        return resp;
    }

    /**
     * 投递前自检：规则、AI 可用性、固定招呼语。
     *
     * @param sayHi String，可空，要检查的固定招呼语
     * @return Map，ok:boolean、problems:List&lt;String&gt;
     */
    @GetMapping("/preflight")
    public Map<String, Object> preflight(@RequestParam(required = false) String sayHi) {
        List<String> problems = jobMatchService.preflight(sayHi);
        return Map.of("ok", problems.isEmpty(), "problems", problems);
    }

    /**
     * 对象转字符串。
     *
     * @param o Object，可空
     * @return String，o 为 null 时返回 null
     */
    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
