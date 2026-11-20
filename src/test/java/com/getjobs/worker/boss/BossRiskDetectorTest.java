package com.getjobs.worker.boss;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 风控识别规则：地址、页面文字、接口返回。 */
class BossRiskDetectorTest {

    @Test
    void classifiesRiskUrls() {
        assertEquals(BossRiskDetector.TYPE_RESTRICTED,
                BossRiskDetector.classifyUrl("https://www.zhipin.com/web/passport/zp/403.html?code=1"));
        assertEquals(BossRiskDetector.TYPE_VERIFY,
                BossRiskDetector.classifyUrl("https://www.zhipin.com/web/passport/zp/verify.html?callbackUrl=x"));
        assertEquals(BossRiskDetector.TYPE_VERIFY,
                BossRiskDetector.classifyUrl("https://www.zhipin.com/web/user/safe/verify-slider?x=1"));
        assertEquals(BossRiskDetector.TYPE_VERIFY,
                BossRiskDetector.classifyUrl("https://www.zhipin.com/web/passport/zp/security.html"));
        assertEquals(BossRiskDetector.TYPE_VERIFY,
                BossRiskDetector.classifyUrl("https://www.zhipin.com/web/common/security-check.html?callbackUrl=x"));
    }

    @Test
    void ignoresNormalUrls() {
        assertNull(BossRiskDetector.classifyUrl(null));
        assertNull(BossRiskDetector.classifyUrl(""));
        assertNull(BossRiskDetector.classifyUrl("https://www.zhipin.com/web/geek/jobs?query=前端&city=100010000"));
        assertNull(BossRiskDetector.classifyUrl("https://www.zhipin.com/job_detail/abc123.html"));
        assertNull(BossRiskDetector.classifyUrl("https://www.zhipin.com/web/geek/chat?id=1"));
        // 2026-11-20 真实 Boss 列表页正常加载的脚本，曾被误判为安全验证
        assertNull(BossRiskDetector.classifyUrl("https://www.zhipin.com/web/passport/zp/security-js/80253c12.js"));
        assertNull(BossRiskDetector.classifyUrl("https://www.zhipin.com/web/passport/zp/verify-js/abc.js"));
        assertNull(BossRiskDetector.classifyUrl("https://www.zhipin.com/web/passport/zp/403.css"));
        assertNull(BossRiskDetector.classifyUrl("https://www.zhipin.com/web/geek/jobs?query=security-check"));
    }

    @Test
    void classifiesShortRiskPagesByText() {
        assertEquals(BossRiskDetector.TYPE_RESTRICTED,
                BossRiskDetector.classifyPageText("BOSS直聘", "访问受限\n您的账户存在异常行为，已被限制访问", 30));
        assertEquals(BossRiskDetector.TYPE_RESTRICTED,
                BossRiskDetector.classifyPageText("访问受限", "", 0));
        assertEquals(BossRiskDetector.TYPE_VERIFY,
                BossRiskDetector.classifyPageText("BOSS直聘", "请完成安全验证 拖动滑块完成拼图", 20));
    }

    @Test
    void longNormalPagesAreNotJudgedByBody() {
        String jd = "岗位职责：负责前端安全验证组件开发，处理访问受限场景的提示页面……";
        assertNull(BossRiskDetector.classifyPageText("前端开发-BOSS直聘", jd, 3000));
        assertNull(BossRiskDetector.classifyPageText(null, null, 0));
    }

    @Test
    void classifiesApiResponses() {
        assertNull(BossRiskDetector.classifyApiResponse(200, "{\"code\":0,\"message\":\"Success\",\"zpData\":{}}"));
        assertNull(BossRiskDetector.classifyApiResponse(200, "{\"zpData\":{}}"));
        assertNull(BossRiskDetector.classifyApiResponse(200, null));
        assertEquals("code=37，您的访问行为异常",
                BossRiskDetector.classifyApiResponse(200, "{\"code\":37,\"message\":\"您的访问行为异常\"}"));
        assertEquals("HTTP 状态 403", BossRiskDetector.classifyApiResponse(403, "{}"));
        assertEquals("HTTP 状态 302", BossRiskDetector.classifyApiResponse(302, null));
        assertEquals("返回内容不是 JSON", BossRiskDetector.classifyApiResponse(200, "<html>访问受限</html>"));
    }

    @Test
    void watchesOnlyJobApis() {
        assertTrue(BossRiskDetector.isWatchedApi("https://www.zhipin.com/wapi/zpgeek/search/joblist.json?page=2"));
        assertTrue(BossRiskDetector.isWatchedApi("https://www.zhipin.com/wapi/zpgeek/job/detail.json?securityId=x"));
        assertFalse(BossRiskDetector.isWatchedApi("https://www.zhipin.com/wapi/zpCommon/data/city.json"));
        assertTrue(BossRiskDetector.isJobListApi("https://www.zhipin.com/wapi/zpgeek/search/joblist.json"));
        assertFalse(BossRiskDetector.isJobListApi("https://www.zhipin.com/wapi/zpgeek/job/detail.json"));
    }
}
