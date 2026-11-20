package com.getjobs.worker.boss;

import com.getjobs.application.service.BossRiskAlertService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.JobMatchService;
import com.getjobs.worker.match.JobCandidate;
import com.getjobs.worker.match.MatchDecision;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用模拟的 Boss 列表页跑同一套浏览代码：所有 www.zhipin.com 请求都由路由拦截返回假页面，
 * 其他域名一律中止，不连接任何招聘网站、不发送任何消息。
 * <p>
 * 模拟页面结构：左侧 ul.rec-job-list 卡片列表，滚动到底再请求下一页（每页 15 个）；
 * 第一张卡片默认展开、点击已展开的卡片不发请求；点开卡片请求 /wapi/zpgeek/job/detail.json，
 * 右侧固定面板显示详情与 a.more-job-btn；详情页带“立即沟通”按钮。
 * <p>
 * 运行方式：BOSS_MOCK_PAGE_TEST=true，BOSS_TEST_CHROME 指向本机 Chromium。
 */
@EnabledIfEnvironmentVariable(named = "BOSS_MOCK_PAGE_TEST", matches = "true")
class BossBrowseMockPageTest {

    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page page;
    File dbFile;
    BossRiskAlertService alertService;
    /** 模拟服务端收到的请求顺序：list:N、detail:K、detailPage:K、403 */
    final List<String> events = new CopyOnWriteArrayList<>();
    final List<String> progress = new CopyOnWriteArrayList<>();

    /** 场景参数 */
    int totalPages = 3;
    boolean allJava = false;
    int riskAt = -1;
    String riskMode = "";
    int passAt = -1;
    /** true 时第一次打开列表页先经过 security-check 中转页，1.5 秒后自动跳回 */
    boolean transientCheck = false;
    boolean checkServed = false;

    @BeforeAll
    static void launch() {
        var environment = new HashMap<>(System.getenv());
        environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(environment));
        var options = new BrowserType.LaunchOptions().setHeadless(true);
        String chrome = System.getenv("BOSS_TEST_CHROME");
        if (chrome != null) options.setExecutablePath(Path.of(chrome));
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    static void shutdown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void setUp() {
        File dir = new File("build/test-db");
        dir.mkdirs();
        dbFile = new File(dir, "mock-browse-" + System.nanoTime() + ".db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        alertService = new BossRiskAlertService(ds);
        alertService.ensureTable();
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 800));
        context.route("**/*", this::serve);
        page = context.newPage();
    }

    @AfterEach
    void tearDown() {
        context.close();
        dbFile.delete();
    }

    // ---------------------------------------------------------------- 场景

    @Test
    void browsesCardsInOrderAndLoadsNextPageOnlyAfterCurrentBatch() {
        passAt = 20;
        Boss boss = newBoss(true);
        boss.execute();

        List<Integer> details = detailIds();
        assertFalse(details.isEmpty());
        // 按卡片从上到下的顺序点开（第一张卡片需要先点第二张再点回来）
        List<Integer> afterDance = details.subList(2, details.size());
        assertEquals(afterDance.stream().sorted().toList(), afterDance, "点开顺序 " + details);
        // 名称不符合的卡片（k % 4 == 3）从不点开，第二张卡片除外（用于让第一张卡片发出详情请求）
        assertTrue(details.stream().filter(k -> k != 1).noneMatch(k -> k % 4 == 3), "点开了不符合的卡片 " + details);
        // 第 2 页是看到第 1 页末尾几张卡片、滚到底时才加载的，没有一开始就预加载；整轮没有加载第 3 页
        int list2 = events.indexOf("list:2");
        int nearEndOfPage1 = events.indexOf("detail:10");
        assertTrue(list2 > nearEndOfPage1 && nearEndOfPage1 >= 0, "事件顺序 " + events);
        assertFalse(events.contains("list:3"), "不应预加载第 3 页 " + events);
        // 只评估模式：AI 通过的岗位打开了详情页但未发送，随后结束
        assertTrue(events.contains("detailPage:" + passAt), "未打开详情页 " + events);
        assertEquals(passAt, details.get(details.size() - 1));
        assertEquals(1, boss.getDeliveredCount());
        assertNull(boss.getAbortReason());
        assertNull(alertService.getActiveAlert());
        // 列表页始终没有跳走，详情页已关闭
        assertTrue(page.url().contains("/web/geek/jobs"));
        assertEquals(1, context.pages().size());
    }

    @Test
    void stopsImmediatelyWhenPageJumpsToRestrictedPage() {
        riskAt = 6;
        riskMode = "redirect";
        Boss boss = newBoss(true);
        boss.execute();

        assertNotNull(boss.getAbortReason());
        assertTrue(boss.getAbortReason().contains("访问受限"), boss.getAbortReason());
        int at = events.indexOf("403");
        assertTrue(at >= 0, "未跳到受限页 " + events);
        assertTrue(events.subList(at, events.size()).stream().noneMatch(e -> e.startsWith("detail") || e.startsWith("list")),
                "受限后仍有请求 " + events);
        // 受限页原样保留，没有刷新或跳走
        assertTrue(page.url().contains("/web/passport/zp/403.html"), page.url());
        Map<String, Object> alert = alertService.getActiveAlert();
        assertNotNull(alert);
        assertEquals("访问受限", alert.get("alertType"));
        assertEquals("前端", alert.get("keyword"));
    }

    @Test
    void stopsImmediatelyWhenDetailApiReturnsErrorCode() {
        riskAt = 4;
        riskMode = "code";
        Boss boss = newBoss(true);
        boss.execute();

        assertNotNull(boss.getAbortReason());
        assertTrue(boss.getAbortReason().contains("接口异常"), boss.getAbortReason());
        int at = events.indexOf("detail:4");
        assertTrue(events.subList(at + 1, events.size()).stream().noneMatch(e -> e.startsWith("detail") || e.startsWith("list")),
                "接口异常后仍有请求 " + events);
        assertEquals("接口异常", alertService.getActiveAlert().get("alertType"));
        // 管理员确认后解除
        alertService.acknowledge();
        assertNull(alertService.getActiveAlert());
    }

    @Test
    void switchesKeywordAfterThreePagesWithoutAnyCardPassing() {
        allJava = true;
        totalPages = 6;
        Boss boss = newBoss(true);
        boss.execute();

        assertTrue(detailIds().isEmpty(), "不应点开任何卡片 " + events);
        assertTrue(events.contains("list:3"), events.toString());
        // 看第 3 页最后几张卡片时滚到底，平台可能顺带加载第 4 页，但不再继续往下看
        assertFalse(events.contains("list:5"), "连续 3 页没有通过的卡片后应换关键词 " + events);
        assertNull(boss.getAbortReason());
        assertEquals(45, boss.getTitleFilteredCount());
    }

    @Test
    void ignoresSecurityScriptsAndAutoReturningCheckPage() {
        // 2026-11-20 真实运行误报：列表页加载 /web/passport/zp/security-js/*.js 被当成安全验证。
        // 本场景列表页同样加载该脚本，并且进入列表前先经过自动跳回的中转校验页，都不应报警。
        allJava = true;
        transientCheck = true;
        Boss boss = newBoss(true);
        boss.execute();

        assertTrue(events.contains("check"), "未经过中转页 " + events);
        assertTrue(events.contains("securityJs"), "未加载安全脚本 " + events);
        assertNull(boss.getAbortReason(), boss.getAbortReason());
        assertNull(alertService.getActiveAlert());
        assertEquals(45, boss.getTitleFilteredCount());
    }

    // ---------------------------------------------------------------- 组装

    /**
     * 组装 Boss：BossService、JobMatchService 用模拟对象，风控提醒用真实的 SQLite。
     *
     * @param dryRun boolean，必填，是否只评估不发送
     * @return Boss，已 prepare 的实例
     */
    Boss newBoss(boolean dryRun) {
        BossService bossService = mock(BossService.class);
        when(bossService.isRecentlyProcessed(any(), any())).thenReturn(false);
        when(bossService.countTodayGreetings()).thenReturn(0);
        JobMatchService match = mock(JobMatchService.class);
        when(match.checkTitle(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).contains("Java")
                ? MatchDecision.reject("名称排除词", "含 Java", null) : null);
        when(match.evaluate(any(), any(), anyBoolean())).thenAnswer(inv -> {
            JobCandidate c = inv.getArgument(0);
            return c.jobName().equals(title(passAt))
                    ? new MatchDecision(true, "通过", "方向与方式都符合", 90, "您好，我是前端工程师")
                    : MatchDecision.reject("AI复核", "不是远程兼职", 40);
        });
        Boss boss = new Boss(bossService, null, match, alertService);
        BossConfig config = new BossConfig();
        config.setKeywords(List.of("前端"));
        config.setCityCode(List.of("100010000"));
        config.setFilterDeadHR(true);
        config.setEnableAI(true);
        config.setSayHi("您好，我是前端工程师");
        config.setWaitTime("1");
        config.setSendImgResume(false);
        boss.setConfig(config);
        boss.setPage(page);
        boss.setProgressCallback((message, current, total) -> progress.add(message));
        boss.setShouldStopCallback(() -> false);
        boss.setDryRun(dryRun);
        boss.setMaxDeliveries(1);
        boss.prepare();
        return boss;
    }

    List<Integer> detailIds() {
        return events.stream().filter(e -> e.startsWith("detail:")).map(e -> Integer.parseInt(e.substring(7))).toList();
    }

    String title(int k) {
        if (allJava || k % 4 == 3) return "Java后端开发" + k;
        return "前端开发工程师" + k;
    }

    // ---------------------------------------------------------------- 模拟服务端

    void serve(Route route) {
        String url = route.request().url();
        if (!url.startsWith("https://www.zhipin.com/")) {
            route.abort();
            return;
        }
        String path = url.substring("https://www.zhipin.com".length());
        if (path.startsWith("/web/geek/jobs")) {
            if (transientCheck && !checkServed) {
                checkServed = true;
                String back = java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8);
                html(route, "<html><body><script>location.replace('/web/common/security-check.html?callbackUrl=" + back
                        + "')</script></body></html>");
                return;
            }
            html(route, listHtml());
        } else if (path.startsWith("/web/common/security-check.html")) {
            events.add("check");
            html(route, "<html><head><title>BOSS直聘</title></head><body>请稍候<script>setTimeout(() => location.replace("
                    + "decodeURIComponent(new URLSearchParams(location.search).get('callbackUrl'))), 1500)</script></body></html>");
        } else if (path.startsWith("/web/passport/zp/security-js/")) {
            events.add("securityJs");
            route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType("application/javascript").setBody("window.__sec = 1;"));
        } else if (path.startsWith("/wapi/zpgeek/search/joblist.json")) {
            int p = Integer.parseInt(param(path, "page"));
            events.add("list:" + p);
            json(route, listJson(p));
        } else if (path.startsWith("/wapi/zpgeek/job/detail.json")) {
            int k = Integer.parseInt(param(path, "id"));
            events.add("detail:" + k);
            if (k == riskAt && "code".equals(riskMode)) {
                json(route, "{\"code\":37,\"message\":\"您的访问行为异常\"}");
            } else {
                json(route, detailJson(k));
            }
        } else if (path.startsWith("/job_detail/")) {
            int k = Integer.parseInt(path.replaceAll("\\D", ""));
            events.add("detailPage:" + k);
            html(route, "<html><head><title>" + title(k) + "</title></head><body style='height:2000px'>"
                    + "<h1>" + title(k) + "</h1><p>岗位描述</p><a class='btn btn-startchat' href='javascript:;'>立即沟通</a>"
                    + "</body></html>");
        } else if (path.startsWith("/web/passport/zp/403.html")) {
            events.add("403");
            html(route, "<html><head><title>BOSS直聘</title></head><body><h2>访问受限</h2>"
                    + "<p>您的账户存在异常行为，已被限制访问</p></body></html>");
        } else {
            route.fulfill(new Route.FulfillOptions().setStatus(404).setBody(""));
        }
    }

    static String param(String path, String name) {
        for (String kv : path.substring(path.indexOf('?') + 1).split("&")) {
            if (kv.startsWith(name + "=")) return kv.substring(name.length() + 1);
        }
        return "";
    }

    static void html(Route route, String body) {
        route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType("text/html; charset=utf-8").setBody(body));
    }

    static void json(Route route, String body) {
        route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType("application/json; charset=utf-8").setBody(body));
    }

    String listJson(int p) {
        StringBuilder sb = new StringBuilder("{\"code\":0,\"message\":\"Success\",\"zpData\":{\"hasMore\":")
                .append(p < totalPages).append(",\"jobList\":[");
        if (p <= totalPages) {
            for (int i = 0; i < 15; i++) {
                int k = (p - 1) * 15 + i;
                if (i > 0) sb.append(',');
                sb.append("{\"id\":").append(k).append(",\"jobName\":\"").append(title(k))
                        .append("\",\"brandName\":\"公司").append(k).append("\",\"encryptJobId\":\"job").append(k)
                        .append("\",\"bossOnline\":").append(k % 2 == 0).append('}');
            }
        }
        return sb.append("]}}").toString();
    }

    String detailJson(int k) {
        String active = k % 5 == 2 ? "半年前活跃" : "刚刚活跃";
        String desc = "负责前端页面开发，使用 Vue 与 React 完成业务需求，远程兼职。".repeat(2);
        return "{\"code\":0,\"message\":\"Success\",\"zpData\":{\"jobInfo\":{\"jobName\":\"" + title(k)
                + "\",\"salaryDesc\":\"10-15K\",\"locationName\":\"全国\",\"experienceName\":\"3-5年\",\"degreeName\":\"本科\","
                + "\"postDescription\":\"" + desc + "\",\"encryptId\":\"job" + k + "\",\"encryptUserId\":\"boss" + k + "\"},"
                + "\"bossInfo\":{\"name\":\"王女士\",\"title\":\"HR\",\"activeTimeDesc\":\"" + active + "\"},"
                + "\"brandComInfo\":{\"brandName\":\"公司" + k + "\"}}}";
    }

    String listHtml() {
        return """
                <html><head><meta charset="utf-8"><title>前端招聘-BOSS直聘</title><style>
                body{margin:0;font-family:sans-serif}
                .header{position:fixed;top:0;left:0;right:0;height:60px;background:#00bebd;z-index:5}
                .job-list-container{margin:70px 0 0 20px;width:700px}
                ul.rec-job-list{list-style:none;padding:0;margin:0}
                li.job-card-box{height:110px;margin:10px 0;border:1px solid #ddd;padding:10px;box-sizing:border-box;cursor:pointer}
                li.job-card-box.active{border-color:#00bebd}
                .job-name{font-size:16px;color:#333}
                .job-detail-box{position:fixed;top:70px;left:760px;width:480px;height:600px;overflow:auto;border:1px solid #ddd;padding:10px}
                #footer{height:200px;background:#f5f5f5}
                </style></head><body>
                <script src="/web/passport/zp/security-js/80253c12.js"></script>
                <div class="header"></div>
                <div class="job-list-container"><ul class="rec-job-list"></ul></div>
                <div class="job-detail-box"><h3 id="d-title"></h3>
                  <a class="more-job-btn" id="d-more" href="/job_detail/0.html">查看更多信息</a>
                  <p id="d-desc" style="height:1200px"></p></div>
                <div id="footer">footer</div>
                <script>
                let pageNo = 0, loading = false, hasMore = true, activeK = 0, total = 0;
                const ul = document.querySelector('ul.rec-job-list');
                function card(j){
                  const li = document.createElement('li');
                  li.className = 'job-card-box' + (j.id === 0 ? ' active' : '');
                  li.innerHTML = '<div class="job-title"><a class="job-name" href="/job_detail/' + j.id + '.html">' + j.jobName
                    + '</a></div><div class="job-info">10-15K · 3-5年 · 本科</div><div class="job-card-footer"><span class="boss-name">'
                    + j.brandName + '</span></div>';
                  li.addEventListener('click', e => {
                    if (e.target.closest('a')) return;
                    if (activeK === j.id) return;
                    activeK = j.id;
                    document.querySelectorAll('li.job-card-box').forEach(n => n.classList.remove('active'));
                    li.classList.add('active');
                    fetch('/wapi/zpgeek/job/detail.json?id=' + j.id).then(r => r.json()).then(d => {
                      if (d.code !== 0) return;
                      document.getElementById('d-title').textContent = d.zpData.jobInfo.jobName;
                      document.getElementById('d-desc').textContent = d.zpData.jobInfo.postDescription;
                      document.getElementById('d-more').setAttribute('href', '/job_detail/' + j.id + '.html');
                      if (window.__riskRedirectAt === j.id) location.href = '/web/passport/zp/403.html';
                    });
                  });
                  return li;
                }
                function loadNext(){
                  if (loading || !hasMore) return;
                  loading = true;
                  fetch('/wapi/zpgeek/search/joblist.json?page=' + (pageNo + 1)).then(r => r.json()).then(d => {
                    pageNo++;
                    d.zpData.jobList.forEach(j => ul.appendChild(card(j)));
                    hasMore = d.zpData.hasMore;
                    loading = false;
                  });
                }
                window.addEventListener('scroll', () => {
                  if (window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 150) loadNext();
                });
                loadNext();
                </script>
                """ + (("redirect".equals(riskMode)) ? "<script>window.__riskRedirectAt = " + riskAt + ";</script>" : "")
                + "</body></html>";
    }
}
