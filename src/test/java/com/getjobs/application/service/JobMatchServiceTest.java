package com.getjobs.application.service;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.worker.match.JobCandidate;
import com.getjobs.worker.match.MatchDecision;
import com.getjobs.worker.match.MatchRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 岗位匹配闸门测试：验证“任何不确定都不发送”的口径。
 */
class JobMatchServiceTest {

    private static final String SAY_HI = "您好，我是前端工程师，熟悉 Vue / React / 小程序，目前找远程兼职。";

    private AiService aiService;
    private JobMatchService service;

    /**
     * 每个用例使用默认规则与已填写的技能介绍。
     * 入参：无。出参：无。
     */
    @BeforeEach
    void setUp() {
        aiService = mock(AiService.class);
        MatchRuleService ruleService = mock(MatchRuleService.class);
        when(ruleService.get()).thenReturn(MatchRule.defaults());
        AiEntity ai = new AiEntity();
        ai.setIntroduce("前端工程师，熟悉 Vue、React、小程序、uni-app，后端会一些 Java 与 MySQL");
        when(aiService.getAiConfig()).thenReturn(ai);
        service = new JobMatchService(ruleService, aiService);
    }

    /**
     * 构造一个前端兼职岗位。
     *
     * @param name String，必填，岗位名称
     * @return JobCandidate，岗位描述为远程兼职前端
     */
    private JobCandidate job(String name) {
        return new JobCandidate("boss", "前端兼职", name, "某公司", "300-500元/天", "远程",
                "负责 Vue3 管理后台开发，远程兼职，按项目结算");
    }

    @Test
    void 名称不含必含词不调用AI直接拒绝() {
        MatchDecision d = service.evaluate(job("兼职剪辑师"), SAY_HI, true);
        assertFalse(d.pass());
        assertEquals("名称必含词", d.stage());
        verify(aiService, never()).chatStrict(anyString(), anyString());
    }

    @Test
    void 名称命中排除词直接拒绝() {
        MatchDecision d = service.evaluate(job("前端测试工程师"), SAY_HI, true);
        assertFalse(d.pass());
        assertEquals("名称排除词", d.stage());
    }

    @Test
    void 岗位描述为空直接拒绝() {
        MatchDecision d = service.evaluate(new JobCandidate("boss", "", "前端开发", "", "", "", ""), SAY_HI, true);
        assertFalse(d.pass());
        assertEquals("信息完整性", d.stage());
    }

    @Test
    void AI调用失败不发送() {
        when(aiService.chatStrict(anyString(), anyString())).thenThrow(new RuntimeException("401"));
        MatchDecision d = service.evaluate(job("Vue前端开发（远程兼职）"), SAY_HI, true);
        assertFalse(d.pass());
        assertEquals("AI复核", d.stage());
        assertNull(d.greeting());
        assertTrue(d.aiUnavailable());
    }

    @Test
    void AI判定不匹配不算AI不可用() {
        when(aiService.chatStrict(anyString(), anyString()))
                .thenReturn("{\"match\":false,\"score\":20,\"reason\":\"要求驻场\",\"greeting\":\"\"}");
        assertFalse(service.evaluate(job("Vue前端开发"), SAY_HI, true).aiUnavailable());
    }

    @Test
    void AI返回非JSON不发送() {
        when(aiService.chatStrict(anyString(), anyString())).thenReturn("您好，我对这个岗位很感兴趣");
        assertFalse(service.evaluate(job("Vue前端开发（远程兼职）"), SAY_HI, true).pass());
    }

    @Test
    void AI判定匹配但分数低于阈值不发送() {
        when(aiService.chatStrict(anyString(), anyString()))
                .thenReturn("{\"match\":true,\"score\":70,\"reason\":\"基本符合\",\"greeting\":\"您好，我是前端工程师，可以远程兼职。\"}");
        MatchDecision d = service.evaluate(job("Vue前端开发（远程兼职）"), SAY_HI, true);
        assertFalse(d.pass());
        assertEquals(70, d.score());
    }

    @Test
    void AI判定不匹配不发送() {
        when(aiService.chatStrict(anyString(), anyString()))
                .thenReturn("{\"match\":false,\"score\":95,\"reason\":\"要求驻场\",\"greeting\":\"\"}");
        assertFalse(service.evaluate(job("Vue前端开发"), SAY_HI, true).pass());
    }

    @Test
    void 通过时使用AI招呼语() {
        String g = "您好，我是前端工程师，熟悉 Vue3，可以远程兼职按项目合作。";
        when(aiService.chatStrict(anyString(), anyString()))
                .thenReturn("```json\n{\"match\":true,\"score\":92,\"reason\":\"前端远程兼职\",\"greeting\":\"" + g + "\"}\n```");
        MatchDecision d = service.evaluate(job("Vue前端开发（远程兼职）"), SAY_HI, true);
        assertTrue(d.pass());
        assertEquals(g, d.greeting());
    }

    @Test
    void AI招呼语含禁用词时回退固定招呼语() {
        when(aiService.chatStrict(anyString(), anyString()))
                .thenReturn("{\"match\":true,\"score\":92,\"reason\":\"匹配\",\"greeting\":\"您好，我是全栈工程师，前后端都能做。\"}");
        MatchDecision d = service.evaluate(job("Vue前端开发（远程兼职）"), SAY_HI, true);
        assertTrue(d.pass());
        assertEquals(SAY_HI, d.greeting());
    }

    @Test
    void 固定招呼语含禁用词时不发送() {
        when(aiService.chatStrict(anyString(), anyString()))
                .thenReturn("{\"match\":true,\"score\":92,\"reason\":\"匹配\",\"greeting\":\"\"}");
        MatchDecision d = service.evaluate(job("Vue前端开发（远程兼职）"), "您好，我是全栈开发，找远程兼职。", false);
        assertFalse(d.pass());
        assertEquals("招呼语校验", d.stage());
    }

    @Test
    void 智联不需要招呼语() {
        when(aiService.chatStrict(anyString(), anyString()))
                .thenReturn("{\"match\":true,\"score\":90,\"reason\":\"匹配\",\"greeting\":\"\"}");
        MatchDecision d = service.evaluate(job("前端开发（兼职）"), null, false);
        assertTrue(d.pass());
        assertNull(d.greeting());
    }
}
