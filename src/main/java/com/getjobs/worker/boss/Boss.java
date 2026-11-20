package com.getjobs.worker.boss;

import com.getjobs.application.service.BossResumeImageService;
import com.getjobs.application.service.BossRiskAlertService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.JobMatchService;
import com.getjobs.worker.match.JobCandidate;
import com.getjobs.worker.match.MatchDecision;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static com.getjobs.worker.boss.Locators.*;


/**
 * @author loks666
 * 项目链接: <a href=
 * "https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * Boss直聘自动投递
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Boss {

    @Setter
    private Page page;
    @Setter
    private BossConfig config;
    private final BossService bossService;
    private final BossResumeImageService bossResumeImageService;
    private final JobMatchService jobMatchService;
    private final BossRiskAlertService bossRiskAlertService;

    /** 本次任务最多实际发送几个岗位，0 表示不限；单次测试时为 1 */
    @Setter
    private int maxDeliveries = 0;
    /** true 表示只评估不发送：走完判定并检查沟通按钮，但不点击沟通、不发任何消息 */
    @Setter
    private boolean dryRun = false;
    /** 本次任务最多评估几个岗位（进入匹配判定的岗位数），0 表示不限；用于限制单次测试的时长 */
    @Setter
    private int maxEvaluations = 0;
    /** 本次任务的逐岗位判定记录，供管理页面展示 */
    private final List<Map<String, Object>> evaluations = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** 已实际发送的岗位数 */
    private volatile int deliveredCount = 0;
    /** 已进入匹配判定的岗位数 */
    private volatile int evaluatedCount = 0;
    /** 连续因 AI 不可用而未能判定的岗位数；达到上限即停止任务 */
    private int consecutiveAiErrors = 0;
    /** 连续 AI 失败上限：AI 服务整体不可用时及时停止，避免空转点击触发平台风控 */
    private static final int MAX_CONSECUTIVE_AI_ERRORS = 3;
    /** 因 AI 不可用而中止时的原因；为 null 表示未中止 */
    private volatile String abortReason = null;

    /** 过滤判定的有效起点：在此之后被过滤的岗位视为近期已处理；由任务服务设置 */
    @Setter
    private java.time.LocalDateTime processedCutoff = java.time.LocalDateTime.now().minusDays(3);
    /** 本次任务遇到的新岗位数（近期未处理过的岗位） */
    private volatile int newJobCount = 0;
    /** 本次任务跳过的近期已处理岗位数 */
    private volatile int skippedOldCount = 0;
    /** 本次任务按卡片名称过滤（未点开）的岗位次数 */
    private volatile int titleFilteredCount = 0;
    /** 达到每日打招呼上限时的提示；为 null 表示未达到 */
    private volatile String limitNotice = null;
    /** 卡片不符合、不点开时的扫视停留范围（毫秒） */
    private static final long CARD_GLANCE_MIN = 300L, CARD_GLANCE_MAX = 1_000L;
    /** 点开后一眼看出不合适（HR 不活跃、黑名单）时的停留范围（毫秒） */
    private static final long QUICK_REJECT_MIN = 1_500L, QUICK_REJECT_MAX = 3_500L;
    /** 连续多少页没有岗位通过卡片筛选就换下一个关键词 */
    private static final int MAX_PAGES_WITHOUT_PASS = 3;
    /** 页面停在风控页超过该时长才判定触发（毫秒），过滤自动跳回的中转校验页 */
    private static final long RISK_CONFIRM_MILLIS = 4_000L;
    /** 正在确认风控线索，避免确认期间的等待再次进入检测 */
    private boolean confirmingRisk = false;
    /** 等待类停顿的检查间隔（毫秒）：每隔这么久检查一次停止标志与风控 */
    private static final long STOP_CHECK_INTERVAL = 500L;
    /** 每点开多少个岗位休息一次；近期已处理的岗位不点开，不计入 */
    private static final int REST_EVERY_VIEWED = 30;
    /** 批次休息范围（毫秒） */
    private static final long BATCH_REST_MIN = 60_000L, BATCH_REST_MAX = 120_000L;
    /** 切换关键词前的休息范围（毫秒） */
    private static final long KEYWORD_REST_MIN = 30_000L, KEYWORD_REST_MAX = 60_000L;
    /** 连续多少个岗位详情取不到就停止任务 */
    private static final int MAX_MISSING_DETAIL = 3;
    /** 距上次批次休息已点开的岗位数 */
    private int viewedSinceRest = 0;
    /** 连续取不到详情的岗位数 */
    private int consecutiveMissingDetail = 0;
    /** 已触发的风控类型；为 null 表示未触发 */
    private volatile String riskType = null;
    /** 页面事件回调发现、尚未处理的风控线索：{类型, 地址, 说明} */
    private volatile String[] pendingRisk = null;
    /** 待检查返回码的岗位接口返回；事件回调里只入队，不调用 Playwright */
    private final java.util.concurrent.ConcurrentLinkedQueue<Response> watchedResponses = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 当前关键词与岗位，写入风控提醒用 */
    private volatile String currentKeyword = null;
    private volatile String currentJobName = null;
    /** 列表页的鼠标 */
    private HumanMouse mouse;
    /** 列表页视口高度（像素） */
    private double viewportHeight = 0;
    /** 列表接口字段是否已输出过日志 */
    private boolean listFieldsLogged = false;
    /** 页面跳转监听：主框架跳到风控页时记下线索 */
    private final java.util.function.Consumer<Frame> riskNavigationListener = frame -> {
        try {
            if (frame.parentFrame() != null) return;
            String u = frame.url();
            String type = BossRiskDetector.classifyUrl(u);
            if (type != null && pendingRisk == null) pendingRisk = new String[]{type, u, "页面跳转到" + type + "页"};
        } catch (Exception ignore) {
        }
    };
    /**
     * 接口返回监听：只把岗位列表 / 详情接口入队待检查。脚本、图片等资源请求不参与判断
     * （Boss 页面正常加载 /web/passport/zp/security-js/*.js，按地址判断会误报）。
     */
    private final java.util.function.Consumer<Response> riskResponseListener = resp -> {
        try {
            if (BossRiskDetector.isWatchedApi(resp.url())) watchedResponses.add(resp);
        } catch (Exception ignore) {
        }
    };
    /** 新标签页监听：程序打开的详情页、聊天页同样挂上跳转监听 */
    private final java.util.function.Consumer<Page> riskPageListener = p -> {
        try {
            p.onFrameNavigated(riskNavigationListener);
        } catch (Exception ignore) {
        }
    };
    private Set<String> blackCompanies;
    private Set<String> blackRecruiters;
    private Set<String> blackJobs;
    // 记录 encryptId -> encryptUserId 的映射，用于后续更新投递状态
    private final ConcurrentMap<String, String> encryptIdToUserId = new ConcurrentHashMap<>();
    @Setter
    private ProgressCallback progressCallback;
    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<Job> resultList = new ArrayList<>();

    /** Boss「全国」城市代码 */
    private static final String NATIONWIDE_CITY_CODE = "100010000";
    /** Boss 首页，UI 搜索的入口 */
    private static final String BOSS_HOME_URL = "https://www.zhipin.com";
    /**
     * true=先回首页、在搜索框里做一次真实 UI 搜索再进列表页；false=直接拼 URL 跳转。
     * <p>
     * 默认关掉。实测 Boss 首页在受控标签页里会一直转圈加载不完，navigate 要 60 秒才返回，
     * 期间 evaluate / locator 全部无限期阻塞（这两个 API 没有默认超时）—— 启动慢、投递卡死、
     * 管理页面按钮没反应都出在这。而岗位列表页在同一个浏览器里是秒开的。
     * 想重新试 UI 搜索路径时再打开。
     */
    private static final boolean USE_UI_SEARCH = false;
    /** 等待页面加载状态的超时（毫秒），绝不能不设 —— 见 waitForPageSettled 的说明 */
    private static final double LOAD_STATE_TIMEOUT = 10_000;
    /** 图片选择完成后，等待聊天区出现本人图片消息的最长时间 */
    private static final int IMAGE_SEND_CONFIRM_TIMEOUT_SECONDS = 45;

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    // 通过 Lombok @RequiredArgsConstructor 使用构造器注入依赖

    public void prepare() {
        // 调整 boss_data 表结构：将 encrypt_id、encrypt_user_id 前置
        try { bossService.ensureBossDataColumnOrder(); } catch (Throwable ignore) {}
        // 从数据库加载黑名单
        this.blackCompanies = bossService.getBlackCompanies();
        this.blackRecruiters = bossService.getBlackRecruiters();
        this.blackJobs = bossService.getBlackJobs();

        log.info("黑名单加载完成: 公司({}) 招聘者({}) 职位({})",
                blackCompanies != null ? blackCompanies.size() : 0,
                blackRecruiters != null ? blackRecruiters.size() : 0,
                blackJobs != null ? blackJobs.size() : 0);
        // 不在页面初始化阶段入库，仅用于后续点击卡片时按需入库
    }

    /**
     * 执行投递
     */
    public int execute() {
        attachRiskListeners();
        try {
            bringBossToFront();
            for (String cityCode : resolveCityCodes()) {
                if (stopRequested()) {
                    progressCallback.accept(stopMessage(), 0, 0);
                    break;
                }
                postJobByCity(cityCode);
                if (stopRequested()) {
                    progressCallback.accept(stopMessage(), 0, 0);
                    break;
                }
            }
        } finally {
            detachRiskListeners();
        }
        return resultList.size();
    }

    /**
     * 停止时展示的原因：风控或异常中止 > 达到每日上限 > 用户取消。
     * 入参：无。
     *
     * @return String，停止原因
     */
    private String stopMessage() {
        if (abortReason != null) return abortReason;
        if (limitNotice != null) return limitNotice;
        return "用户取消投递";
    }

    /**
     * 挂上风控监听：列表页与之后新开的标签页的页面跳转、Boss 岗位接口返回。
     * 入参：无。出参：无。
     */
    private void attachRiskListeners() {
        try {
            page.onFrameNavigated(riskNavigationListener);
            page.context().onResponse(riskResponseListener);
            page.context().onPage(riskPageListener);
        } catch (Exception e) {
            log.warn("挂载风控监听失败：{}", e.getMessage());
        }
    }

    /**
     * 摘除风控监听，避免任务结束后仍持有回调。
     * 入参：无。出参：无。
     */
    private void detachRiskListeners() {
        try {
            page.offFrameNavigated(riskNavigationListener);
            page.context().offResponse(riskResponseListener);
            page.context().offPage(riskPageListener);
        } catch (Exception e) {
            log.debug("摘除风控监听失败：{}", e.getMessage());
        }
        watchedResponses.clear();
    }

    /**
     * 把 Boss 列表页切到前台。
     * 入参：无。出参：无。
     */
    private void bringBossToFront() {
        try {
            if (page != null && !page.isClosed()) page.bringToFront();
        } catch (Exception e) {
            log.debug("切换 Boss 标签页到前台失败：{}", e.getMessage());
        }
    }

    /**
     * 列表页的鼠标，首次使用时创建。
     * 入参：无。
     *
     * @return HumanMouse，鼠标
     */
    private HumanMouse mouse() {
        if (mouse == null) mouse = new HumanMouse(page);
        return mouse;
    }

    /**
     * 检查风控线索：事件回调记下的跳转、待检查的岗位接口返回、Boss 标签页当前地址。命中即触发风控停止。
     * 入参：无。出参：无。
     */
    private void detectRisk() {
        if (riskType != null || confirmingRisk) return;
        Response resp;
        while ((resp = watchedResponses.poll()) != null) {
            String body = null;
            int status;
            try {
                status = resp.status();
            } catch (Exception e) {
                continue;
            }
            if (status >= 200 && status < 300) {
                try {
                    body = resp.text();
                } catch (Exception e) {
                    continue;
                }
            }
            String problem = BossRiskDetector.classifyApiResponse(status, body);
            if (problem != null) {
                String which = BossRiskDetector.isJobListApi(resp.url()) ? "岗位列表接口" : "岗位详情接口";
                tripRisk(BossRiskDetector.TYPE_API, resp.url(), which + "返回异常：" + problem);
                return;
            }
            if (BossRiskDetector.isJobListApi(resp.url())) recordJobListSample(body);
        }
        String[] hit = scanRiskPages();
        if (hit == null && pendingRisk == null) return;
        String[] first = hit != null ? hit : pendingRisk;
        // 平台有时会经过中转校验页、几秒内自动跳回；停在风控页超过确认时长才判定触发，等待期间不做任何操作
        confirmingRisk = true;
        try {
            long deadline = System.currentTimeMillis() + RISK_CONFIRM_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                pumpEvents(STOP_CHECK_INTERVAL);
                hit = scanRiskPages();
                if (hit == null) {
                    log.info("页面经过 {}（{}）后已自动离开，不视为风控", first[0], first[1]);
                    pendingRisk = null;
                    return;
                }
            }
            tripRisk(hit[0], hit[1], "页面停在" + hit[0] + "页超过 " + RISK_CONFIRM_MILLIS / 1000 + " 秒");
        } finally {
            confirmingRisk = false;
        }
    }

    /**
     * 查看 Boss 列表页及 zhipin.com 下其他标签页的当前地址是否为风控页。只读本地记录的地址，不操作页面。
     * 入参：无。
     *
     * @return String[]，{类型, 地址}；没有标签页停在风控页时为 null
     */
    private String[] scanRiskPages() {
        try {
            if (page == null) return null;
            List<Page> pages = new ArrayList<>();
            pages.add(page);
            if (page.context() != null && page.context().pages() != null) pages.addAll(page.context().pages());
            for (Page p : pages) {
                if (p == null || p.isClosed()) continue;
                String u = p.url();
                if (p != page && (u == null || !u.contains("zhipin.com"))) continue;
                String type = BossRiskDetector.classifyUrl(u);
                if (type != null) return new String[]{type, u};
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /**
     * 等待一小段时间并让页面事件送达；不检查停止标志（供风控确认期间使用）。
     *
     * @param millis long，必填，等待毫秒数
     */
    private void pumpEvents(long millis) {
        try {
            if (page == null || page.isClosed()) throw new IllegalStateException("页面不可用");
            page.waitForTimeout(millis);
        } catch (Exception e) {
            PlaywrightUtil.sleepMillis(millis);
        }
    }

    /**
     * 按页面标题与正文检查是否是风控页，命中即触发风控停止。只读取页面，不操作页面。
     *
     * @param target Page，可空，要检查的标签页
     * @return boolean，true 表示已触发风控
     */
    private boolean checkRiskByPageText(Page target) {
        if (riskType != null) return true;
        if (target == null) return false;
        try {
            Object raw = target.evaluate("() => { const t = (document.body && document.body.innerText) || '';"
                    + " return { title: document.title || '', text: t.slice(0, 800), len: t.length }; }");
            String type = pageTextRiskType(raw);
            if (type != null) {
                // 与地址判断一样：过一段时间再看一次，仍是风控提示才判定触发
                pumpEvents(RISK_CONFIRM_MILLIS);
                String again = pageTextRiskType(target.evaluate("() => { const t = (document.body && document.body.innerText) || '';"
                        + " return { title: document.title || '', text: t.slice(0, 800), len: t.length }; }"));
                if (again != null) {
                    tripRisk(again, target.url(), "页面内容为" + again + "提示");
                    return true;
                }
                log.info("页面短暂出现{}提示后已恢复，不视为风控：{}", type, target.url());
            }
        } catch (Exception e) {
            log.debug("检查页面内容失败：{}", e.getMessage());
        }
        return riskType != null;
    }

    /**
     * 按页面标题与正文的读取结果判断风控类型。
     *
     * @param raw Object，可空，页面脚本返回的 {title, text, len}
     * @return String，风控类型；未命中时为 null
     */
    private static String pageTextRiskType(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Object len = m.get("len");
        return BossRiskDetector.classifyPageText(String.valueOf(m.get("title")),
                String.valueOf(m.get("text")), len instanceof Number n ? n.intValue() : Integer.MAX_VALUE);
    }

    /**
     * 触发风控停止：置位停止标志、写入风控提醒、推送进度。之后不再点击、刷新、跳转任何页面。
     *
     * @param type   String，必填，风控类型
     * @param url    String，可空，页面或接口地址
     * @param detail String，必填，触发说明
     */
    private void tripRisk(String type, String url, String detail) {
        if (riskType != null) return;
        riskType = type;
        pendingRisk = null;
        abortReason = "检测到 Boss " + type + "（" + detail + "），投递已立即停止。请在 noVNC 的 Boss 标签页查看并人工处理，"
                + "处理完后到 Boss 配置页点「我已处理」才能再次启动";
        log.warn("【风控】{} | 关键词：{} | 岗位：{} | 地址：{} | {}", type, currentKeyword, currentJobName, url, detail);
        if (bossRiskAlertService != null) {
            bossRiskAlertService.record(type, url, currentKeyword, currentJobName, detail);
        }
        if (progressCallback != null) progressCallback.accept(abortReason, 0, 0);
    }

    /**
     * 被动记录页面本来就会加载的岗位列表接口数据，用于核对卡片数据里有没有 HR 活跃时间。
     * 只保留最近一次，并在每次任务第一次拿到时输出岗位字段名。
     *
     * @param body String，可空，列表接口返回
     */
    private void recordJobListSample(String body) {
        if (body == null || body.isBlank()) return;
        try {
            java.io.File dir = new java.io.File("target");
            if (!dir.exists()) dir.mkdirs();
            java.nio.file.Files.writeString(new java.io.File(dir, "joblist-sample.json").toPath(), body,
                    StandardCharsets.UTF_8);
            if (!listFieldsLogged) {
                JSONObject zpData = new JSONObject(body).optJSONObject("zpData");
                org.json.JSONArray list = zpData != null ? zpData.optJSONArray("jobList") : null;
                if (list != null && !list.isEmpty() && list.optJSONObject(0) != null) {
                    listFieldsLogged = true;
                    log.info("岗位列表接口单个岗位字段：{}", new TreeSet<>(list.getJSONObject(0).keySet()));
                }
            }
        } catch (Exception e) {
            log.debug("记录岗位列表接口数据失败：{}", e.getMessage());
        }
    }

    /**
     * 取搜索用的城市代码列表。城市为空或选「不限」（代码 0）时，Boss 搜索地址不带 city 参数，
     * 平台会按账号或 IP 所在城市搜索，因此统一换成全国（100010000）。
     * 入参：无。
     *
     * @return List&lt;String&gt;，城市代码列表，不为空
     */
    private List<String> resolveCityCodes() {
        List<String> result = new ArrayList<>();
        if (config.getCityCode() != null) {
            for (String code : config.getCityCode()) {
                String c = code == null ? "" : code.trim();
                String resolved = c.isEmpty() || "0".equals(c) ? NATIONWIDE_CITY_CODE : c;
                if (!result.contains(resolved)) result.add(resolved);
            }
        }
        if (result.isEmpty()) result.add(NATIONWIDE_CITY_CODE);
        return result;
    }

    /**
     * 是否应停止：用户点了停止，或已达到本次任务的发送 / 评估上限。
     *
     * @return boolean，true 表示应立即停止后续岗位处理
     */
    private boolean stopRequested() {
        detectRisk();
        if (abortReason != null || limitNotice != null) return true;
        if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) return true;
        if (maxDeliveries > 0 && deliveredCount >= maxDeliveries) return true;
        return maxEvaluations > 0 && evaluatedCount >= maxEvaluations;
    }

    /**
     * 获取本次任务的逐岗位判定记录（只读副本）。
     *
     * @return List&lt;Map&gt;，每项含 time、jobName、companyName、salary、stage、reason、score、greeting、action
     */
    public List<Map<String, Object>> getEvaluations() {
        return new ArrayList<>(evaluations);
    }

    /**
     * 本次任务已实际发送的岗位数。
     *
     * @return int，发送成功数
     */
    public int getDeliveredCount() {
        return deliveredCount;
    }

    /**
     * 因 AI 不可用而中止任务的原因。
     *
     * @return String，中止原因；未中止时为 null
     */
    public String getAbortReason() {
        return abortReason;
    }

    /**
     * 达到每日打招呼上限时的提示。
     *
     * @return String，提示文本；未达到上限时为 null
     */
    public String getLimitNotice() {
        return limitNotice;
    }

    /**
     * 本次任务遇到的新岗位数。
     *
     * @return int，近期未处理过的岗位数
     */
    public int getNewJobCount() {
        return newJobCount;
    }

    /**
     * 获取本次任务按卡片名称过滤（未点开）的岗位次数。
     * 入参：无。
     *
     * @return int，过滤次数，同一岗位被多个关键词搜到时重复计数
     */
    public int getTitleFilteredCount() {
        return titleFilteredCount;
    }

    /**
     * 本次任务跳过的近期已处理岗位数。
     *
     * @return int，跳过数
     */
    public int getSkippedOldCount() {
        return skippedOldCount;
    }

    /**
     * 可被停止指令打断的休息。
     *
     * @param minMillis long，必填，最短毫秒
     * @param maxMillis long，必填，最长毫秒
     */
    private void restWithStopCheck(long minMillis, long maxMillis) {
        sleepWithStopCheck(minMillis + (long) (Math.max(0, maxMillis - minMillis) * ThreadLocalRandom.current().nextDouble()));
    }

    /**
     * 可被打断的等待：每 {@value #STOP_CHECK_INTERVAL} 毫秒检查一次停止标志与风控。
     * 优先用页面等待，让页面事件（跳转、接口返回）能在等待期间送达；页面不可用时退回线程休眠。
     *
     * @param millis long，必填，等待毫秒数，不大于 0 时不等待
     */
    private void sleepWithStopCheck(long millis) {
        long remaining = millis;
        while (remaining > 0) {
            if (stopRequested()) return;
            long chunk = Math.min(STOP_CHECK_INTERVAL, remaining);
            try {
                if (page == null || page.isClosed()) throw new IllegalStateException("页面不可用");
                page.waitForTimeout(chunk);
            } catch (Exception e) {
                PlaywrightUtil.sleepMillis(chunk);
            }
            remaining -= chunk;
        }
    }

    /**
     * 按卡片上的岗位名称执行名称规则。
     *
     * @param title String，可空，卡片上的岗位名称
     * @return boolean，true 表示名称规则不通过；名称为空时返回 false，由点开后按详情判定
     */
    private boolean isRejectedByCardTitle(String title) {
        try {
            return title != null && !title.isBlank() && jobMatchService.checkTitle(title.trim()) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 是否已触发风控（含检查页面地址与事件线索）。
     * 入参：无。
     *
     * @return boolean，true 表示已触发风控、任务应立即结束
     */
    private boolean riskTripped() {
        detectRisk();
        return riskType != null;
    }

    /**
     * 像人一样的随机停顿：在区间内偏向较短的一端取值，避免均匀分布的机械特征；等待期间可被停止或风控打断。
     *
     * @param minMillis long，必填，最短毫秒
     * @param maxMillis long，必填，最长毫秒；不大于 minMillis 时按 minMillis 停顿
     */
    private void humanPause(long minMillis, long maxMillis) {
        long span = Math.max(0, maxMillis - minMillis);
        double r = Math.pow(ThreadLocalRandom.current().nextDouble(), 1.6);
        sleepWithStopCheck(minMillis + (long) (span * r));
    }

    /**
     * 从岗位链接中取 encryptId（/job_detail/xxx.html）。
     *
     * @param href String，可空，链接
     * @return String，encryptId；取不到时为 null
     */
    private static String encryptIdFromHref(String href) {
        if (href == null) return null;
        int idx = href.indexOf("/job_detail/");
        if (idx < 0) return null;
        int start = idx + "/job_detail/".length();
        int end = href.indexOf(".html", start);
        String id = end < 0 ? href.substring(start) : href.substring(start, end);
        return id.isBlank() ? null : id;
    }

    /**
     * 记录一个岗位的判定结果，并同步输出进度。
     *
     * @param jobName     String，可空，岗位名称
     * @param companyName String，可空，公司名称
     * @param salary      String，可空，薪资
     * @param stage       String，必填，判定环节
     * @param reason      String，可空，原因
     * @param score       Integer，可空，AI 分数
     * @param greeting    String，可空，招呼语
     * @param action      String，必填，处理结果：已过滤 / 已发送 / 发送失败 / 测试通过未发送 / 已沟通跳过
     * @return Map，新增的记录（后续可补充 action）
     */
    private Map<String, Object> recordEvaluation(String jobName, String companyName, String salary, String stage,
                                                 String reason, Integer score, String greeting, String action) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("time", java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        m.put("jobName", jobName == null ? "" : jobName);
        m.put("companyName", companyName == null ? "" : companyName);
        m.put("salary", salary == null ? "" : salary);
        m.put("stage", stage);
        m.put("reason", reason == null ? "" : reason);
        m.put("score", score);
        m.put("greeting", greeting);
        m.put("action", action);
        evaluations.add(m);
        if (evaluations.size() > 300) evaluations.remove(0);
        return m;
    }

    /**
     * 获取结果列表
     */
    public List<Job> getResultList() {
        return new ArrayList<>(resultList);
    }

    /**
     * 更新黑名单（从聊天记录中）
     */
    public Map<String, Set<String>> updateBlacklistFromChats() {
        page.navigate("https://www.zhipin.com/web/geek/chat");
        PlaywrightUtil.sleep(3);

        int newBlacklistCount = 0;
        boolean shouldBreak = false;
        while (!shouldBreak) {
            try {
                Locator bottomLocator = page.locator(FINISHED_TEXT);
                if (bottomLocator.count() > 0 && "没有更多了".equals(bottomLocator.textContent())) {
                    shouldBreak = true;
                }
            } catch (Exception ignore) {
            }

            Locator items = page.locator(CHAT_LIST_ITEM);
            int itemCount = items.count();

            for (int i = 0; i < itemCount; i++) {
                try {
                    Locator companyElements = page.locator(COMPANY_NAME_IN_CHAT);
                    Locator messageElements = page.locator(LAST_MESSAGE);

                    if (i >= companyElements.count() || i >= messageElements.count()) {
                        break;
                    }

                    String companyName = null;
                    String message = null;
                    int retryCount = 0;

                    while (true) {
                        try {
                            companyName = companyElements.nth(i).textContent();
                            message = messageElements.nth(i).textContent();
                            break;
                        } catch (Exception e) {
                            retryCount++;
                            if (retryCount >= 2) {
                                log.info("尝试获取元素文本2次失败，放弃本次获取");
                                break;
                            }
                            log.info("页面元素已变更，正在重试第{}次获取元素文本...", retryCount);
                            PlaywrightUtil.sleep(1);
                        }
                    }

                    if (companyName != null && message != null) {
                        boolean match = message.contains("不") || message.contains("感谢") || message.contains("但")
                                || message.contains("遗憾") || message.contains("需要本") || message.contains("对不");
                        boolean nomatch = message.contains("不是") || message.contains("不生");
                        if (match && !nomatch) {
                            if (blackCompanies.stream().anyMatch(companyName::contains)) {
                                continue;
                            }
                            companyName = companyName.replaceAll("\\.{3}", "");
                            if (companyName.matches(".*(\\p{IsHan}{2,}|[a-zA-Z]{4,}).*")) {
                                blackCompanies.add(companyName);
                                // 保存到数据库
                                bossService.addBlacklist("company", companyName);
                                newBlacklistCount++;
                                log.info("黑名单公司：【{}】，信息：【{}】", companyName, message);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("寻找黑名单公司异常...", e);
                }
            }

            try {
                Locator scrollElement = page.locator(SCROLL_LOAD_MORE);
                if (scrollElement.count() > 0) {
                    scrollElement.scrollIntoViewIfNeeded();
                } else {
                    page.evaluate("window.scrollTo(0, document.body.scrollHeight);");
                }
            } catch (Exception e) {
                log.error("滚动元素出错", e);
                break;
            }
        }
        log.info("黑名单公司数量：{}，本次新增：{}", (blackCompanies != null ? blackCompanies.size() : 0), newBlacklistCount);

        Map<String, Set<String>> result = new HashMap<>();
        result.put("blackCompanies", new HashSet<>(blackCompanies != null ? blackCompanies : Collections.emptySet()));
        result.put("blackRecruiters", new HashSet<>(blackRecruiters != null ? blackRecruiters : Collections.emptySet()));
        result.put("blackJobs", new HashSet<>(blackJobs != null ? blackJobs : Collections.emptySet()));
        return result;
    }

    /**
     * 按城市逐个关键词浏览岗位列表。
     *
     * @param cityCode String，必填，城市代码
     */
    private void postJobByCity(String cityCode) {
        String searchUrl = getSearchUrl(cityCode);
        boolean firstKeyword = true;
        for (String keyword : config.getKeywords()) {
            if (!firstKeyword) restWithStopCheck(KEYWORD_REST_MIN, KEYWORD_REST_MAX);
            firstKeyword = false;
            if (stopRequested()) {
                progressCallback.accept(stopMessage(), 0, 0);
                return;
            }
            currentKeyword = keyword;
            currentJobName = null;
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            String url = searchUrl + (searchUrl.contains("?") ? "&" : "?") + "query=" + encodedKeyword;
            // 单个关键词失败不该让整个投递任务中止；风控触发时 stopRequested 为真，直接结束
            try {
                browseKeyword(keyword, url, cityCode);
            } catch (Exception e) {
                if (stopRequested()) return;
                log.error("【{}】处理失败，跳过该关键词继续下一个：{}", keyword, e.getMessage(), e);
                if (progressCallback != null) {
                    progressCallback.accept("关键词[" + keyword + "]失败已跳过：" + e.getMessage(), 0, 0);
                }
            }
            if (stopRequested()) return;
        }
    }

    /**
     * 像真人一样浏览一个关键词的搜索结果：只处理已加载的一批卡片，从上往下逐个看；
     * 当前一批看完后用滚轮向下滚动加载下一页，不预加载、不回到顶部。
     * 平台没有更多岗位，或连续 {@value #MAX_PAGES_WITHOUT_PASS} 页没有岗位通过卡片筛选时，结束该关键词。
     *
     * @param keyword  String，必填，搜索关键词
     * @param url      String，必填，搜索结果页地址
     * @param cityCode String，必填，城市代码
     */
    private void browseKeyword(String keyword, String url, String cityCode) {
        openJobListWithRetry(keyword, url, cityCode);
        if (stopRequested() || checkRiskByPageText(page)) return;
        viewportHeight = mouse().viewport()[1];
        // 页面打开后先扫一眼
        humanPause(1500, 3500);

        int deliveredBefore = deliveredCount;
        int next = 0;
        int pageNo = 0;
        int pagesWithoutPass = 0;
        while (!stopRequested()) {
            int count = countJobCards();
            if (next >= count) {
                if (!loadMoreByWheel(keyword, count)) {
                    log.info("【{}】没有更多岗位，结束该关键词", keyword);
                    break;
                }
                continue;
            }
            pageNo++;
            boolean anyPass = false;
            log.info("【{}】第 {} 页：第 {} ~ {} 个岗位", keyword, pageNo, next + 1, count);
            progressCallback.accept("正在浏览：" + keyword + " 第 " + pageNo + " 页", next, count);
            for (; next < count; next++) {
                if (stopRequested()) {
                    progressCallback.accept(stopMessage(), next, count);
                    return;
                }
                CardResult result = handleCard(keyword, next, count);
                if (result == CardResult.STOP) {
                    if (stopRequested()) progressCallback.accept(stopMessage(), next + 1, count);
                    return;
                }
                if (result == CardResult.OPENED) anyPass = true;
            }
            pagesWithoutPass = anyPass ? 0 : pagesWithoutPass + 1;
            if (pagesWithoutPass >= MAX_PAGES_WITHOUT_PASS) {
                log.info("【{}】连续 {} 页没有岗位通过卡片筛选，换下一个关键词", keyword, pagesWithoutPass);
                break;
            }
        }
        log.info("【{}】浏览结束，本关键词发送 {} 个", keyword, deliveredCount - deliveredBefore);
    }

    /** 单张卡片的处理结果 */
    private enum CardResult {
        /** 卡片不符合，未点开 */
        SKIPPED,
        /** 卡片通过筛选并已点开（无论后续是否发送） */
        OPENED,
        /** 应结束当前关键词（停止指令、风控、达到上限、AI 不可用等） */
        STOP
    }

    /**
     * 处理一张岗位卡片：先看卡片，不符合的不点开；通过的点开看右侧岗位信息，
     * HR 活跃度与招聘者不通过则看下一个；否则读完岗位描述交给匹配闸门，通过后进入沟通流程。
     *
     * @param keyword String，必填，当前关键词
     * @param i       int，必填，卡片序号（从 0 开始）
     * @param count   int，必填，当前已加载的卡片总数
     * @return CardResult，处理结果
     */
    private CardResult handleCard(String keyword, int i, int count) {
        Locator card = jobCards().nth(i);
        currentJobName = null;
        scrollIntoViewByWheel(card);
        if (stopRequested()) return CardResult.STOP;

        // 1. 先看卡片：名称规则、职位 / 公司黑名单、近期已处理，不符合的不点开
        Map<String, String> info = readCard(card);
        String cardTitle = info.getOrDefault("title", "");
        String cardCompany = info.getOrDefault("company", "");
        String cardEncryptId = encryptIdFromHref(info.get("href"));
        currentJobName = cardTitle;
        String skip = null;
        if (cardEncryptId != null && bossService.isRecentlyProcessed(cardEncryptId, processedCutoff)) {
            skippedOldCount++;
            skip = "近期已处理";
        } else if (isRejectedByCardTitle(cardTitle)) {
            titleFilteredCount++;
            skip = "名称规则不通过";
        } else {
            String hit = cardBlacklistHit(cardTitle, cardCompany, blackJobs, blackCompanies);
            if (hit != null) {
                titleFilteredCount++;
                skip = hit;
            }
        }
        if (skip != null) {
            log.debug("【{}】卡片未点开：{} | {} | {}", keyword, cardTitle, cardCompany, skip);
            if (ThreadLocalRandom.current().nextDouble() < 0.3) mouse().hover(card);
            humanPause(CARD_GLANCE_MIN, CARD_GLANCE_MAX);
            return CardResult.SKIPPED;
        }

        // 2. 点开卡片，右侧显示岗位信息
        Response detailResp = clickCardForDetail(card, i, count);
        if (stopRequested()) return CardResult.STOP;
        viewedSinceRest++;

        String jobName = null;
        String jobSalary = null;
        java.util.List<String> tags = new java.util.ArrayList<>();
        String jobDesc = null;
        String bossName = null;
        String bossActive = null;
        String bossCompany = null;
        String bossJobTitle = null;
        String jobEncryptId = null;
        String jobEncryptUserId = null;

        if (detailResp != null) {
            String body = null;
            try {
                body = detailResp.text();
            } catch (Throwable e) {
                log.debug("读取岗位详情返回失败：{}", e.getMessage());
            }
            String problem = BossRiskDetector.classifyApiResponse(detailResp.status(), body);
            if (problem != null) {
                tripRisk(BossRiskDetector.TYPE_API, detailResp.url(), "岗位详情接口返回异常：" + problem);
                return CardResult.STOP;
            }
            try {
                if (cardEncryptId == null && body != null) {
                    org.json.JSONObject probe = new org.json.JSONObject(body).optJSONObject("zpData");
                    org.json.JSONObject probeJob = probe != null ? probe.optJSONObject("jobInfo") : null;
                    String probeId = probeJob != null ? probeJob.optString("encryptId", null) : null;
                    if (probeId != null && bossService.isRecentlyProcessed(probeId, processedCutoff)) {
                        skippedOldCount++;
                        humanPause(CARD_GLANCE_MIN, CARD_GLANCE_MAX);
                        return CardResult.OPENED;
                    }
                }
                if (body != null) {
                    newJobCount++;
                    appendRawJson(body);
                    processJobDetailJsonAndInsert(body);

                    org.json.JSONObject root = new org.json.JSONObject(body);
                    org.json.JSONObject zpData = root.optJSONObject("zpData");
                    org.json.JSONObject jobInfo = zpData != null ? zpData.optJSONObject("jobInfo") : null;
                    org.json.JSONObject brand = zpData != null ? zpData.optJSONObject("brandComInfo") : null;
                    org.json.JSONObject boss = zpData != null ? zpData.optJSONObject("bossInfo") : null;
                    if (jobInfo != null) {
                        jobName = jobInfo.optString("jobName", "");
                        jobSalary = jobInfo.optString("salaryDesc", "");
                        String city = jobInfo.optString("locationName", "");
                        String exp = jobInfo.optString("experienceName", "");
                        String deg = jobInfo.optString("degreeName", "");
                        if (!city.isEmpty()) tags.add(city);
                        if (!exp.isEmpty()) tags.add(exp);
                        if (!deg.isEmpty()) tags.add(deg);
                        jobDesc = jobInfo.optString("postDescription", "");
                        jobEncryptId = jobInfo.optString("encryptId", null);
                        jobEncryptUserId = jobInfo.optString("encryptUserId", null);
                    }
                    if (boss != null) {
                        bossName = boss.optString("name", "");
                        bossActive = boss.optString("activeTimeDesc", "");
                        bossJobTitle = boss.optString("title", "");
                    }
                    if (brand != null) {
                        bossCompany = brand.optString("brandName", "");
                    }
                }
            } catch (Throwable e) {
                log.debug("点击卡片后解析岗位详情用于过滤失败：{}", e.getMessage());
            }
        }

        // 取不到详情时不判定：多半是页面被拦，继续点只会增加风险
        if (jobName == null || jobName.isBlank()) {
            consecutiveMissingDetail++;
            log.warn("【{}】第 {} 个岗位详情未取到（连续 {} 个）", keyword, i + 1, consecutiveMissingDetail);
            if (stopRequested() || checkRiskByPageText(page)) return CardResult.STOP;
            if (consecutiveMissingDetail >= MAX_MISSING_DETAIL) {
                abortReason = "连续 " + consecutiveMissingDetail + " 个岗位详情取不到，可能触发平台校验，任务已自动停止，请在 noVNC 查看 Boss 页面";
                log.warn(abortReason);
                progressCallback.accept(abortReason, i + 1, count);
                return CardResult.STOP;
            }
            humanPause(CARD_GLANCE_MIN, CARD_GLANCE_MAX);
            return CardResult.OPENED;
        }
        consecutiveMissingDetail = 0;
        currentJobName = jobName;

        // 3. 右侧岗位信息里一眼能看出不合适的：黑名单、HR 不活跃，稍看一下就换下一个
        String quickReject = null;
        String quickStage = "黑名单";
        if (blackJobs != null && blackJobs.stream().anyMatch(jobName::contains)) {
            quickReject = "职位黑名单命中：" + findMatchedTerm(blackJobs, jobName);
        } else if (Boolean.TRUE.equals(config.getFilterDeadHR()) && bossActive != null && bossActive.contains("年")) {
            quickStage = "HR活跃度";
            quickReject = "HR 不活跃：" + bossActive;
        } else if (bossCompany != null && blackCompanies != null && blackCompanies.stream().anyMatch(bossCompany::contains)) {
            quickReject = "公司黑名单命中：" + findMatchedTerm(blackCompanies, bossCompany);
        } else if (bossJobTitle != null && blackRecruiters != null && blackRecruiters.stream().anyMatch(bossJobTitle::contains)) {
            quickReject = "招聘者黑名单命中：" + findMatchedTerm(blackRecruiters, bossJobTitle);
        }
        if (quickReject != null) {
            log.info("被过滤：{} | 公司：{} | 岗位：{}", quickReject, bossCompany != null ? bossCompany : "", jobName);
            recordEvaluation(jobName, bossCompany, jobSalary, quickStage, quickReject, null, null, "已过滤");
            bossService.updateMatchResult(jobEncryptId, jobEncryptUserId, "已过滤", quickStage + "：" + quickReject);
            humanPause(QUICK_REJECT_MIN, QUICK_REJECT_MAX);
            restIfNeeded();
            return stopRequested() ? CardResult.STOP : CardResult.OPENED;
        }

        // 4. 按岗位描述长度阅读一会儿，再交给匹配闸门
        readJobDetail(jobDesc == null ? 0 : jobDesc.length());
        if (stopRequested()) return CardResult.STOP;
        restIfNeeded();
        if (stopRequested()) return CardResult.STOP;

        // 匹配闸门：发送前必须通过。规则 / AI 任一环节不通过或出错都不发送。
        // 必须放在点击“立即沟通”之前：Boss 打开沟通本身就可能触发平台侧的自动打招呼。
        evaluatedCount++;
        progressCallback.accept("正在判定：" + jobName, i + 1, count);
        MatchDecision decision = jobMatchService.evaluate(
                new JobCandidate("boss", keyword, jobName, bossCompany, jobSalary, String.join(", ", tags), jobDesc),
                config.getSayHi(), Boolean.TRUE.equals(config.getEnableAI()));
        if (decision.aiUnavailable()) {
            // AI 不可用：本岗位不发送，也不记为“已过滤”（岗位本身未被判定），记为未投递
            consecutiveAiErrors++;
            log.warn("AI 不可用，本岗位不发送（连续 {} 次） | 公司：{} | 岗位：{} | 原因：{}",
                    consecutiveAiErrors, bossCompany, jobName, decision.reason());
            recordEvaluation(jobName, bossCompany, jobSalary, decision.stage(), decision.reason(),
                    null, null, "AI不可用未发送");
            bossService.updateMatchResult(jobEncryptId, jobEncryptUserId, "未投递", decision.reason());
            if (consecutiveAiErrors >= MAX_CONSECUTIVE_AI_ERRORS) {
                abortReason = "AI 连续 " + consecutiveAiErrors + " 次不可用，任务已自动停止，未发送任何消息。最后一次原因："
                        + decision.reason();
                log.error(abortReason);
                progressCallback.accept(abortReason, i + 1, count);
                return CardResult.STOP;
            }
            return CardResult.OPENED;
        }
        consecutiveAiErrors = 0;
        if (!decision.pass()) {
            log.info("匹配判定不通过 | 环节：{} | 公司：{} | 岗位：{} | 原因：{}",
                    decision.stage(), bossCompany, jobName, decision.reason());
            recordEvaluation(jobName, bossCompany, jobSalary, decision.stage(), decision.reason(),
                    decision.score(), null, "已过滤");
            bossService.updateMatchResult(jobEncryptId, jobEncryptUserId, "已过滤",
                    decision.stage() + "：" + decision.reason());
            pauseBetweenJobs();
            return stopRequested() ? CardResult.STOP : CardResult.OPENED;
        }
        log.info("匹配判定通过 | 分数：{} | 公司：{} | 岗位：{} | 原因：{}",
                decision.score(), bossCompany, jobName, decision.reason());
        Map<String, Object> evaluation = recordEvaluation(jobName, bossCompany, jobSalary, decision.stage(),
                decision.reason(), decision.score(), decision.greeting(), "处理中");

        Job job = new Job();
        job.setJobName(jobName);
        job.setSalary(jobSalary != null ? jobSalary : "");
        job.setJobArea(String.join(", ", tags));
        job.setCompanyName(bossCompany != null ? bossCompany : "");
        job.setRecruiter(bossName != null ? bossName : "");
        job.setJobInfo(jobDesc != null ? jobDesc : "");

        // 5. AI 通过后打开详情页沟通；详情页关闭后把 Boss 列表页切回前台
        progressCallback.accept("正在投递：" + jobName, i + 1, count);
        String action = resumeSubmission(keyword, job, decision.greeting());
        bringBossToFront();
        evaluation.put("action", action);
        if (action.startsWith("已发送")) {
            deliveredCount++;
            bossService.logGreeting(jobEncryptId, jobName, bossCompany);
            Integer limit = config.getDailyGreetLimit();
            if (limit != null && limit > 0) {
                int today = bossService.countTodayGreetings();
                if (today >= limit) {
                    limitNotice = "今日已打招呼 " + today + " 次，达到每日上限 " + limit + " 次，任务已自动停止";
                    log.info(limitNotice);
                }
            }
            bossService.updateMatchResult(jobEncryptId, jobEncryptUserId, "已投递",
                    "通过（" + decision.score() + " 分）：" + decision.reason());
        } else if ("测试通过未发送".equals(action)) {
            bossService.updateMatchResult(jobEncryptId, jobEncryptUserId, "未投递",
                    "单次测试通过（未发送，" + decision.score() + " 分）：" + decision.reason());
        }
        if (dryRun && "测试通过未发送".equals(action)) {
            // 只评估模式下，找到一个可发送岗位即视为完成一次测试
            deliveredCount++;
        }
        if (stopRequested()) {
            String why = abortReason != null ? abortReason : limitNotice != null ? limitNotice
                    : (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) ? "已按停止指令结束"
                    : "已达到本次任务的发送数量，结束";
            progressCallback.accept(why, i + 1, count);
            return CardResult.STOP;
        }
        // 按 wait_time 降速，别把风控刷出来
        pauseBetweenJobs();
        return stopRequested() ? CardResult.STOP : CardResult.OPENED;
    }

    /**
     * 点开卡片数达到批次阈值时休息一会儿。
     * 入参：无。出参：无。
     */
    private void restIfNeeded() {
        if (viewedSinceRest < REST_EVERY_VIEWED) return;
        viewedSinceRest = 0;
        log.info("已点开 {} 个岗位，批次休息", REST_EVERY_VIEWED);
        restWithStopCheck(BATCH_REST_MIN, BATCH_REST_MAX);
    }

    /**
     * 当前列表页的全部岗位卡片。
     * 入参：无。
     *
     * @return Locator，岗位卡片集合
     */
    private Locator jobCards() {
        return page.locator(JOB_LIST_SELECTOR);
    }

    /**
     * 一次读出卡片上的岗位名称、公司名称与岗位链接。
     *
     * @param card Locator，必填，岗位卡片
     * @return Map&lt;String, String&gt;，title、company、href，取不到的为空字符串
     */
    private Map<String, String> readCard(Locator card) {
        Map<String, String> info = new HashMap<>();
        try {
            Object raw = card.evaluate("el => { const q = s => { const n = el.querySelector(s);"
                    + " return n ? (n.innerText || n.textContent || '').trim() : ''; };"
                    + " const a = el.querySelector(\"a[href*='/job_detail/']\");"
                    + " return { title: q('.job-name'), company: q('.boss-name') || q('.company-name'),"
                    + " href: a ? (a.getAttribute('href') || '') : '' }; }");
            if (raw instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    info.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
            }
        } catch (Exception e) {
            log.debug("读取卡片信息失败：{}", e.getMessage());
        }
        return info;
    }

    /**
     * 卡片上的职位名称、公司名称是否命中黑名单。
     *
     * @param title          String，可空，卡片上的岗位名称
     * @param company        String，可空，卡片上的公司名称
     * @param blackJobs      Set&lt;String&gt;，可空，职位黑名单
     * @param blackCompanies Set&lt;String&gt;，可空，公司黑名单
     * @return String，命中说明；未命中时为 null
     */
    static String cardBlacklistHit(String title, String company, Set<String> blackJobs, Set<String> blackCompanies) {
        if (title != null && !title.isBlank() && blackJobs != null) {
            for (String term : blackJobs) {
                if (term != null && !term.isBlank() && title.contains(term)) return "职位黑名单命中：" + term;
            }
        }
        if (company != null && !company.isBlank() && blackCompanies != null) {
            for (String term : blackCompanies) {
                if (term != null && !term.isBlank() && company.contains(term)) return "公司黑名单命中：" + term;
            }
        }
        return null;
    }

    /**
     * 用鼠标点开卡片并等待右侧岗位详情接口返回。第一个卡片默认已展开、点击不会触发请求，
     * 因此先点第二个再点回第一个。
     *
     * @param card  Locator，必填，要点开的卡片
     * @param i     int，必填，卡片序号
     * @param count int，必填，已加载卡片数
     * @return Response，岗位详情接口返回；超时或失败时为 null
     */
    private Response clickCardForDetail(Locator card, int i, int count) {
        java.util.function.Predicate<Response> isDetail = r -> {
            try {
                return r.url() != null && r.url().contains("/wapi/zpgeek/job/detail.json")
                        && "GET".equalsIgnoreCase(r.request().method());
            } catch (Throwable ignore) {
                return false;
            }
        };
        try {
            if (i == 0 && count > 1) {
                mouse().click(jobCards().nth(1), this::humanPause);
                humanPause(800, 1800);
                if (stopRequested()) return null;
            }
            return page.waitForResponse(isDetail, new Page.WaitForResponseOptions().setTimeout(10_000),
                    () -> mouse().click(card, this::humanPause));
        } catch (Throwable e) {
            log.debug("点开卡片未等到岗位详情：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 按岗位描述长度估算阅读时间并停留；描述较长时偶尔把鼠标移到右侧详情上往下滚一点。
     *
     * @param descLength int，必填，岗位描述字数
     */
    private void readJobDetail(int descLength) {
        long total = readingMillis(descLength, ThreadLocalRandom.current());
        if (descLength > 400 && ThreadLocalRandom.current().nextDouble() < 0.5) {
            try {
                Locator panel = page.locator(".job-detail-box, .job-detail-container").first();
                if (panel.count() > 0) {
                    long first = total / 3;
                    sleepWithStopCheck(first);
                    if (stopRequested()) return;
                    mouse().hover(panel);
                    mouse().wheel(150 + ThreadLocalRandom.current().nextInt(250), this::humanPause);
                    total -= first;
                }
            } catch (Exception e) {
                log.debug("滚动岗位详情失败：{}", e.getMessage());
            }
        }
        sleepWithStopCheck(total);
    }

    /**
     * 按岗位描述长度估算的阅读停留时间：按每秒 18~28 字略读，限制在 2.5~15 秒，再加 0.5~2.5 秒随机量。
     *
     * @param descLength int，必填，岗位描述字数
     * @param random     Random，必填，随机数源
     * @return long，停留毫秒数，范围 3000~17500
     */
    static long readingMillis(int descLength, Random random) {
        double charsPerSecond = 18 + random.nextDouble() * 10;
        long read = (long) (Math.max(0, descLength) / charsPerSecond * 1000);
        read = Math.max(2_500L, Math.min(15_000L, read));
        return read + 500 + (long) (random.nextDouble() * 2_000);
    }

    /**
     * 卡片不在可视区域时，把鼠标放到列表上用滚轮滚过去，不用脚本滚动。
     *
     * @param target Locator，必填，要看的卡片
     */
    private void scrollIntoViewByWheel(Locator target) {
        for (int round = 0; round < 6; round++) {
            com.microsoft.playwright.options.BoundingBox box;
            try {
                box = target.boundingBox(new Locator.BoundingBoxOptions().setTimeout(3_000));
            } catch (Exception e) {
                return;
            }
            if (box == null) return;
            double vh = viewportHeight > 0 ? viewportHeight : 800;
            if (box.y >= vh * 0.12 && box.y + box.height <= vh - 30) return;
            double targetY = vh * (0.3 + ThreadLocalRandom.current().nextDouble() * 0.25);
            int delta = (int) (box.y - targetY);
            if (Math.abs(delta) < 20) return;
            moveMouseOverList();
            mouse().wheel(delta, this::humanPause);
            humanPause(250, 700);
            if (stopRequested()) return;
        }
    }

    /**
     * 鼠标不在岗位列表上时移过去，保证滚轮滚的是列表而不是右侧详情。
     * 入参：无。出参：无。
     */
    private void moveMouseOverList() {
        try {
            com.microsoft.playwright.options.BoundingBox list = page.locator("ul.rec-job-list").first()
                    .boundingBox(new Locator.BoundingBoxOptions().setTimeout(2_000));
            if (list == null) return;
            double vh = viewportHeight > 0 ? viewportHeight : 800;
            double[] pos = mouse().position();
            boolean inside = pos[0] >= list.x + 10 && pos[0] <= list.x + list.width - 10
                    && pos[1] >= vh * 0.15 && pos[1] <= vh * 0.9;
            if (inside) return;
            double x = list.x + list.width * (0.25 + ThreadLocalRandom.current().nextDouble() * 0.5);
            double y = vh * (0.4 + ThreadLocalRandom.current().nextDouble() * 0.3);
            mouse().moveTo(x, y);
        } catch (Exception e) {
            log.debug("移动鼠标到岗位列表失败：{}", e.getMessage());
        }
    }

    /**
     * 当前一批看完后，用滚轮分几次向下滚动，等平台加载下一页。
     *
     * @param keyword     String，必填，当前关键词（用于日志）
     * @param countBefore int，必填，滚动前的卡片数
     * @return boolean，true 表示加载出了新卡片；到底后仍没有新卡片时为 false
     */
    private boolean loadMoreByWheel(String keyword, int countBefore) {
        int bottomHits = 0;
        for (int round = 0; round < 15; round++) {
            if (stopRequested()) return false;
            moveMouseOverList();
            mouse().wheel(250 + ThreadLocalRandom.current().nextInt(350), this::humanPause);
            humanPause(600, 1500);
            int now = countJobCards();
            if (now > countBefore) {
                log.info("【{}】滚动加载了下一页：{} → {}", keyword, countBefore, now);
                humanPause(800, 2000);
                return true;
            }
            if (atPageBottom()) {
                if (++bottomHits >= 3) return false;
                humanPause(1000, 2000);
            }
        }
        return false;
    }

    /**
     * 页面是否已滚到底。
     * 入参：无。
     *
     * @return boolean，true 表示已到底；读取失败时为 false
     */
    private boolean atPageBottom() {
        try {
            Object r = page.evaluate("() => window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 8");
            return Boolean.TRUE.equals(r);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析岗位详情 JSON 并进行入库与黑名单处理（只在点击卡片时调用）。
     */
    private void processJobDetailJsonAndInsert(String body) {
        if (body == null || body.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject zpData = root.optJSONObject("zpData");
            if (zpData == null) return;

            JSONObject jobInfo = zpData.optJSONObject("jobInfo");
            JSONObject brand = zpData.optJSONObject("brandComInfo");
            JSONObject bossInfo = zpData.optJSONObject("bossInfo");
            if (jobInfo == null) return;

            String encryptId = jobInfo.optString("encryptId", null);
            String encryptUserId = jobInfo.optString("encryptUserId", null);
            if (encryptUserId == null && bossInfo != null) {
                // 兼容部分页面字段落在 bossInfo 内
                encryptUserId = bossInfo.optString("encryptUserId", null);
                if (encryptUserId == null) {
                    // 进一步兼容可能的字段命名
                    encryptUserId = bossInfo.optString("encryptBossId", null);
                }
            }
            if (encryptId != null && encryptUserId != null) {
                encryptIdToUserId.put(encryptId, encryptUserId);
            }

            com.getjobs.application.entity.BossJobDataEntity entity = new com.getjobs.application.entity.BossJobDataEntity();
            entity.setJobName(jobInfo.optString("jobName", null));
            entity.setSalary(jobInfo.optString("salaryDesc", null));
            entity.setLocation(jobInfo.optString("locationName", null));
            entity.setExperience(jobInfo.optString("experienceName", null));
            entity.setDegree(jobInfo.optString("degreeName", null));
            entity.setJobDescription(jobInfo.optString("postDescription", null));
            entity.setRecruitmentStatus(jobInfo.optString("jobStatusDesc", null));
            entity.setCompanyAddress(jobInfo.optString("address", null));
            entity.setEncryptId(encryptId);
            entity.setEncryptUserId(encryptUserId);

            entity.setCompanyName(brand != null ? brand.optString("brandName", null) : null);
            entity.setIndustry(brand != null ? brand.optString("industryName", null) : null);
            entity.setIntroduce(brand != null ? brand.optString("introduce", null) : null);
            entity.setFinancingStage(brand != null ? brand.optString("stageName", null) : null);
            entity.setCompanyScale(brand != null ? brand.optString("scaleName", null) : null);

            entity.setHrName(bossInfo != null ? bossInfo.optString("name", null) : null);
            entity.setHrPosition(bossInfo != null ? bossInfo.optString("title", null) : null);
            entity.setHrActiveStatus(bossInfo != null ? bossInfo.optString("activeTimeDesc", null) : null);

            if (encryptId != null && !encryptId.isEmpty()) {
                entity.setJobUrl("https://www.zhipin.com/job_detail/" + encryptId + ".html");
            }

            // 黑名单处理
            boolean filtered = false;
            String companyName = entity.getCompanyName() != null ? entity.getCompanyName() : "";
            String positionName = entity.getJobName() != null ? entity.getJobName() : "";
            String hrPosition = entity.getHrPosition() != null ? entity.getHrPosition() : "";
            try {
                if (blackCompanies != null && blackCompanies.stream().anyMatch(companyName::contains)) filtered = true;
                if (!filtered && blackJobs != null && blackJobs.stream().anyMatch(positionName::contains)) filtered = true;
                if (!filtered && blackRecruiters != null && blackRecruiters.stream().anyMatch(hrPosition::contains)) filtered = true;
            } catch (Throwable ignore) {}

            // HR活跃状态过滤：开启过滤且活跃描述包含“年”，则标记为已过滤，但仍入库
            if (!filtered && Boolean.TRUE.equals(config.getFilterDeadHR())) {
                String hrActive = entity.getHrActiveStatus();
                if (hrActive != null && hrActive.contains("年")) {
                    filtered = true;
                }
            }

            entity.setDeliveryStatus(filtered ? "已过滤" : "未投递");

            // 入库（若不存在），优先以 encrypt_id + encrypt_user_id 去重；若 userId 缺失，则以 encrypt_id 去重
            if (encryptId != null) {
                try {
                    boolean exists = false;
                    if (encryptUserId != null) {
        exists = bossService.existsBossJob(encryptId, encryptUserId);
                    } else {
        exists = bossService.existsBossJobByEncryptId(encryptId);
                    }
                    if (!exists) {
        bossService.insertBossJob(entity);
                        log.debug("岗位入库：{} | 公司：{} | HR：{} | 状态：{}", entity.getJobName(), entity.getCompanyName(), entity.getHrName(), entity.getDeliveryStatus());
                    }
                } catch (Exception e) {
                    log.warn("岗位入库失败：{}", e.getMessage());
                }
            }
        } catch (Throwable e) {
            log.debug("解析岗位详情 JSON 失败：{}", e.getMessage());
        }
    }

    public String decodeSalary(String text) {
        Map<Character, Character> fontMap = new HashMap<>();
        fontMap.put('\uE8F0', '0');
        fontMap.put('\uE8F1', '1');
        fontMap.put('\uE8F2', '2');
        fontMap.put('\uE8F3', '3');
        fontMap.put('\uE8F4', '4');
        fontMap.put('\uE8F5', '5');
        fontMap.put('\uE8F6', '6');
        fontMap.put('\uE8F7', '7');
        fontMap.put('\uE8F8', '8');
        fontMap.put('\uE8F9', '9');
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            result.append(fontMap.getOrDefault(c, c));
        }
        return result.toString();
    }

    // 安全获取单个文本内容
    public String safeText(Locator root, String selector) {
        Locator node = root.locator(selector);
        try {
            if (node.count() > 0 && node.innerText() != null) {
                return node.innerText().trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    // 安全获取多个文本内容
    public List<String> safeAllText(Locator root, String selector) {
        try {
            return root.locator(selector).allInnerTexts();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // Boss姓名+活跃状态拆分
    public String[] splitBossName(String raw) {
        String[] bossParts = raw.trim().split("\\s+");
        String bossName = bossParts[0];
        String bossActive = bossParts.length > 1 ? String.join(" ", Arrays.copyOfRange(bossParts, 1, bossParts.length)) : "";
        return new String[]{bossName, bossActive};
    }

    // Boss公司+职位拆分
    public String[] splitBossTitle(String raw) {
        String[] parts = raw.trim().split(" · ");
        String company = parts[0];
        String job = parts.length > 1 ? parts[1] : "";
        return new String[]{company, job};
    }

    // 匹配命中词条（用于日志输出过滤原因）
    private String findMatchedTerm(java.util.Collection<String> patterns, String text) {
        if (patterns == null || text == null) return null;
        try {
            for (String p : patterns) {
                if (p != null && !p.isEmpty() && text.contains(p)) {
                    return p;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    public static String buildSearchUrl(BossConfig config, String cityCode) {
        String baseUrl = "https://www.zhipin.com/web/geek/jobs";
        if (config == null) {
            return baseUrl;
        }
        List<String> params = new ArrayList<>();
        addParam(params, JobUtils.appendParam("city", cityCode));
        addParam(params, JobUtils.appendParam("jobType", config.getJobType()));
        addParam(params, JobUtils.appendListParam("salary", config.getSalary()));
        addParam(params, JobUtils.appendListParam("experience", config.getExperience()));
        addParam(params, JobUtils.appendListParam("degree", config.getDegree()));
        addParam(params, JobUtils.appendListParam("scale", config.getScale()));
        addParam(params, JobUtils.appendListParam("industry", config.getIndustry()));
        addParam(params, JobUtils.appendListParam("stage", config.getStage()));
        if (params.isEmpty()) {
            return baseUrl;
        }
        return baseUrl + "?" + String.join("&", params);
    }

    private static void addParam(List<String> params, String param) {
        if (param == null || param.isEmpty()) {
            return;
        }
        params.add(param.startsWith("&") ? param.substring(1) : param);
    }

    private String getSearchUrl(String cityCode) {
        return buildSearchUrl(config, cityCode);
    }

    /**
     * 进入岗位列表页。
     * <p>
     * 优先模拟真人路径：回首页 -> 在搜索框里逐字输入关键词 -> 点搜索按钮，
     * 而不是冷启动直接把拼好的列表页 URL 丢给浏览器。
     * <p>
     * UI 搜索只能带上关键词和首页当前城市，config 里的城市/薪资/经验等筛选项仍然只能靠 URL，
     * 所以落地之后如果条件对不上，会再做一次同标签页跳转 —— 此时已经是热会话 + 同源 referer，
     * 和冷启动直闯不是一回事。UI 搜索任何一步失败都会退回原来的直接跳转，不影响主流程。
     */
    private void openJobList(String keyword, String targetUrl, String cityCode) {
        boolean uiSearchDone = USE_UI_SEARCH && searchFromHomePage(keyword);
        if (!uiSearchDone) {
            navigateToJobList(targetUrl);
        } else if (needsUrlFilters(targetUrl, cityCode)) {
            log.info("【{}】UI搜索已落地，补充筛选条件跳转：{}", keyword, targetUrl);
            navigateToJobList(targetUrl);
        }
        warnIfSecurityCheck(keyword);
    }

    /**
     * 在 Boss 首页搜索框里做一次真实的 UI 搜索。
     *
     * @return 是否成功落到岗位列表页
     */
    private boolean searchFromHomePage(String keyword) {
        try {
            String current = page.url();
            if (current == null || !current.startsWith(BOSS_HOME_URL) || current.contains("/web/geek/")) {
                navigateTo(BOSS_HOME_URL);
            }
            // 首页落地后还会自己跳一次（/shanghai/?seoRefer=index 之类），
            // 不等它跳完就找搜索框，会卡在 "waiting for navigation to finish" 直到超时
            waitForPageSettled();

            Locator input = page.locator(HOME_SEARCH_INPUT).first();
            input.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
            input.click();
            input.fill("");
            // 逐字输入，模拟真人打字节奏；一次性 fill 在输入行为层面太干净了
            input.pressSequentially(keyword, new Locator.PressSequentiallyOptions().setDelay(140));
            PlaywrightUtil.sleep(1);

            Locator searchBtn = page.locator(HOME_SEARCH_BUTTON).first();
            if (searchBtn.count() > 0) {
                searchBtn.click();
            } else {
                input.press("Enter");
            }
            // 表单提交是当前标签页跳转；若 Boss 改成新开标签页，这里会超时并退回直接跳转
            page.waitForURL("**/web/geek/jobs**", new Page.WaitForURLOptions().setTimeout(15_000));
            log.info("【{}】已通过首页搜索框进入岗位列表：{}", keyword, page.url());
            return true;
        } catch (Exception e) {
            log.warn("【{}】首页UI搜索失败，退回直接跳转：{}", keyword, e.getMessage());
            return false;
        }
    }

    /**
     * UI 搜索落地后，判断还需不需要用 URL 把筛选条件补上。
     */
    private boolean needsUrlFilters(String targetUrl, String cityCode) {
        String landed = page.url();
        if (landed == null) {
            return true;
        }
        // 首页搜索用的是首页当前城市，不一定等于配置里的城市
        if (cityCode != null && !cityCode.isEmpty() && !landed.contains("city=" + cityCode)) {
            return true;
        }
        // 除 city/query 外还有别的筛选项（薪资、经验、学历……），UI 搜索带不上
        int queryStart = targetUrl.indexOf('?');
        if (queryStart < 0) {
            return false;
        }
        for (String param : targetUrl.substring(queryStart + 1).split("&")) {
            int eq = param.indexOf('=');
            String name = eq < 0 ? param : param.substring(0, eq);
            String value = eq < 0 ? "" : param.substring(eq + 1);
            if (value.isEmpty() || "city".equals(name) || "query".equals(name)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private void navigateToJobList(String url) {
        navigateTo(url);
    }

    /**
     * 导航并重试一次。
     * <p>
     * Boss 页面自己会做客户端跳转，撞上时 Playwright 报 net::ERR_ABORTED；
     * 这类失败重试一次基本就过了，不该让整个投递任务因此中止。
     */
    private void navigateTo(String url) {
        // Boss 的 SPA 在网络不佳或被限流时，15 秒常常不够，之前实测连着两次都超时
        Page.NavigateOptions options = new Page.NavigateOptions()
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(45_000);
        try {
            page.navigate(url, options);
        } catch (Exception first) {
            // 导航期间页面自己又跳了一次时，Playwright 会抛 "Object doesn't exist: request@/frame@"，
            // 但页面其实已经到位了。先看落地 URL，别白白重试一遍。
            if (landedOn(url)) {
                log.debug("导航报了失效对象异常但页面已到位，忽略：{}", first.getMessage());
                settleAfterNavigation();
                return;
            }
            log.warn("导航失败，1秒后重试一次：{} | {}", url, first.getMessage());
            PlaywrightUtil.sleep(1);
            try {
                page.navigate(url, options);
            } catch (Exception second) {
                if (!landedOn(url)) {
                    throw second;
                }
                log.debug("重试同样报失效对象异常但页面已到位，忽略：{}", second.getMessage());
            }
        }
        settleAfterNavigation();
    }

    /**
     * 判断页面是否已经落在目标地址上（只比较路径，查询参数会被 Boss 改写）。
     */
    private boolean landedOn(String targetUrl) {
        try {
            String current = page.url();
            if (current == null) {
                return false;
            }
            String targetPath = targetUrl.split("\\?")[0];
            return current.startsWith(targetPath);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 等页面真正稳定下来再继续。
     * <p>
     * DOMCONTENTLOADED 返回之后 Boss 还会自己做客户端跳转，此时 frame 被替换，
     * 后续任何 locator 调用都会报 "Object doesn't exist: frame@..."。
     */
    private void settleAfterNavigation() {
        waitForPageSettled();
    }

    /**
     * 等页面进入 domcontentloaded，最多等 {@value #LOAD_STATE_TIMEOUT} 毫秒。
     * <p>
     * 两个坑都踩过了：
     * 一是不能等 LOAD/NETWORKIDLE —— Boss 是 SPA + WebSocket 长连接，这两个状态可能永远不到；
     * 二是必须显式给超时 —— 不给超时就是无限期挂起，而"卡住"不是异常，
     * 外面包 try/catch 完全没用（实测把线程挂了两分钟以上还在等）。
     */
    private void waitForPageSettled() {
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(LOAD_STATE_TIMEOUT));
        } catch (Exception ignore) {
            // 等不到就算了，后面的 locator 调用自己有超时
        }
        PlaywrightUtil.sleep(2);
    }

    /**
     * 判断是不是 Playwright 的"对象已失效"异常。
     * <p>
     * 页面频繁增删 frame 时，Playwright Java 在派发事件时会引用到已经销毁的对象，
     * 抛 "Object doesn't exist: frame@..."。这个异常和当时在飞的那个调用没有因果关系，
     * 连接本身仍然可用，重试即可。
     */
    private static boolean isStaleObjectError(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        // 两类都是"页面在动"导致的瞬时异常，跟调用本身没有因果关系，重试就好：
        // - Object doesn't exist: frame@/request@  事件派发时引用到已销毁的对象
        // - Execution context was destroyed         求值期间页面发生了导航
        return message.contains("Object doesn't exist")
                || message.contains("Execution context was destroyed");
    }

    /**
     * 进入岗位列表并等列表渲染出来，整段带重试。
     * Boss 首页/列表页在登录态下会连着跳好几次，一次失败很正常。
     */
    private void openJobListWithRetry(String keyword, String targetUrl, String cityCode) {
        // 只重试一次：每次都要走导航(最长45秒×2) + 等列表(15秒×3)，
        // 试三轮的话一个关键词失败要耗掉好几分钟，界面上看着就是"卡住不动"
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                openJobList(keyword, targetUrl, cityCode);
                waitForJobList();
                return;
            } catch (RuntimeException e) {
                last = e;
                if (riskTripped()) throw e;
                log.warn("【{}】进入岗位列表失败（第{}/2次）：{}", keyword, attempt,
                        e.getMessage() == null ? e.toString() : e.getMessage().split("\n")[0]);
                if (attempt < 2) {
                    sleepWithStopCheck(3_000);
                }
            }
        }
        throw last;
    }

    /**
     * 统计当前列表里的岗位卡片数量，对失效对象异常做重试。
     */
    private int countJobCards() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return page.locator(JOB_LIST_SELECTOR).count();
            } catch (Exception e) {
                if (!isStaleObjectError(e) || attempt == 3) {
                    if (isStaleObjectError(e)) {
                        log.warn("统计岗位数量始终失败，按 0 处理：{}", e.getMessage());
                        return 0;
                    }
                    throw e;
                }
                PlaywrightUtil.sleep(1);
            }
        }
        return 0;
    }

    /**
     * 等待岗位列表容器出现，带重试。
     * 页面在这期间可能还在跳转，一次失败不代表真的没有列表。
     */
    private void waitForJobList() {
        // Boss 改版频繁，推荐页和搜索结果页的容器类名不一样。
        // 用逗号把候选选择器拼成一个，让 Playwright 一次性等"任意一个先出现"，
        // 不要逐个 8 秒串行试 —— 那样一轮就要 48 秒，页面正常时也慢得像卡死。
        String containers = String.join(", ",
                "ul.rec-job-list",
                "ul.job-list-box",
                ".job-list-box",
                ".search-job-result",
                "li.job-card-box",
                "li.job-card-wrapper");

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                page.waitForSelector(containers,
                        new Page.WaitForSelectorOptions().setTimeout(15_000));
                log.info("岗位列表容器已出现（第{}次尝试）", attempt);
                return;
            } catch (Exception e) {
                log.warn("等待岗位列表失败（第{}/3次），当前页面: {}", attempt, safeUrl());
                settleAfterNavigation();
            }
        }
        dumpListPageStructure();
        throw new IllegalStateException("岗位列表始终未出现，当前页面: " + safeUrl());
    }

    private String safeUrl() {
        try {
            return page.url();
        } catch (Exception e) {
            return "(取不到URL)";
        }
    }

    /**
     * 列表容器一个都没命中时，把页面上的候选列表结构打出来，方便修选择器。
     */
    private void dumpListPageStructure() {
        try {
            Object info = page.evaluate("""
                    () => {
                      const uls = Array.from(document.querySelectorAll('ul,div'))
                        .filter(el => el.className && typeof el.className === 'string'
                                   && /job|list|rec/i.test(el.className))
                        .slice(0, 15)
                        .map(el => el.tagName.toLowerCase() + '.' + el.className.trim().replace(/\\s+/g, '.')
                                 + ' (children=' + el.childElementCount + ')');
                      return { title: document.title, url: location.href, candidates: uls };
                    }""");
            log.warn("列表页结构快照: {}", info);
        } catch (Exception e) {
            log.warn("抓取列表页结构失败: {}", e.getMessage());
        }
    }

    /**
     * 落地后检查是否被弹到风控页（访问受限、安全验证），命中时抛出异常结束当前关键词。
     *
     * @param keyword String，必填，当前关键词（仅用于日志）
     */
    private void warnIfSecurityCheck(String keyword) {
        if (riskTripped() || checkRiskByPageText(page)) {
            log.warn("【{}】打开岗位列表后发现风控页", keyword);
            throw new IllegalStateException(abortReason);
        }
    }

    /**
     * 备注：目前Boss无法通过新标签页打开立即沟通按钮，所以只能点击更多详情，然后从更多详情里打开聊天按钮
     */
    @SneakyThrows
    /**
     * 对已通过匹配判定的岗位发起沟通：打开详情页 → 检查沟通按钮 → 发送招呼语 → 发送图片简历。
     *
     * @param keyword  String，必填，当前搜索关键词（仅用于日志）
     * @param job      Job，必填，岗位信息
     * @param greeting String，必填，匹配闸门给出的招呼语，原样发送
     * @return String，处理结果：已发送 / 发送失败 / 测试通过未发送 / 已沟通跳过 / 已跳过
     */
    private String resumeSubmission(String keyword, Job job, String greeting) {
        // 若收到停止指令，直接短路返回
        if (stopRequested()) {
            log.info("停止指令已触发，跳过投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return "已跳过";
        }
        // 调试模式：仅遍历不投递
        if (Boolean.TRUE.equals(config.getDebugger())) {
            log.info("调试模式：仅遍历岗位，不投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return "已跳过";
        }

        // 1. 查找"查看更多信息"按钮（必须存在且新开页）
        Locator moreInfoBtn = page.locator("a.more-job-btn");
        if (moreInfoBtn.count() == 0) {
            log.warn("未找到\"查看更多信息\"按钮，跳过...");
            return "已跳过";
        }
        String href = moreInfoBtn.first().getAttribute("href");
        if (href == null || !href.startsWith("/job_detail/")) {
            log.warn("未获取到岗位详情链接，跳过...");
            return "已跳过";
        }
        String detailUrl = "https://www.zhipin.com" + href;
        // 2. 真实点击“查看更多信息”，由浏览器弹出新标签页（不再用脚本直接跳转链接）。
        //    先确保链接在新标签页打开，避免列表页被导航走。
        Page detailPage;
        try {
            moreInfoBtn.first().evaluate("el => { el.target = '_blank'; }");
            detailPage = page.waitForPopup(new Page.WaitForPopupOptions().setTimeout(10_000),
                    () -> mouse().click(moreInfoBtn.first(), this::humanPause));
        } catch (Throwable e) {
            log.warn("点击“查看更多信息”未打开详情页，跳过该岗位：{}", e.getMessage());
            return "已跳过";
        }
        if (detailPage == null) {
            log.warn("点击“查看更多信息”未打开详情页，跳过该岗位");
            return "已跳过";
        }
        // 风控触发后不关闭任何页面，验证页、受限页原样保留给人工处理
        try (BossChatNavigator navigator = new BossChatNavigator(detailPage, () -> riskType != null)) {
            try {
                detailPage.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(LOAD_STATE_TIMEOUT));
            } catch (Throwable ignore) {
            }
            if (stopRequested() || checkRiskByPageText(detailPage)) {
                closeOwnedPage(detailPage);
                return "已跳过";
            }
            // 像人一样在详情页看一会儿，把鼠标移到正文上往下滚一点
            humanPause(1500, 3000);
            try {
                HumanMouse detailMouse = new HumanMouse(detailPage);
                double[] size = detailMouse.viewport();
                detailMouse.moveTo(size[0] * (0.3 + ThreadLocalRandom.current().nextDouble() * 0.3),
                        size[1] * (0.4 + ThreadLocalRandom.current().nextDouble() * 0.3));
                detailMouse.wheel(200 + ThreadLocalRandom.current().nextInt(400), this::humanPause);
            } catch (Throwable ignore) {
            }
            humanPause(1500, 3000);
            if (stopRequested()) {
                closeOwnedPage(detailPage);
                return "已跳过";
            }

            // 3. 根据开关选择是否允许对已沟通过的Boss再次发送消息
            Locator chatBtn = detailPage.locator("a.btn-startchat, a.op-btn-chat");
            boolean foundChatBtn = false;
            boolean repeatChat = false;
            String repeatMessage = null;
            for (int i = 0; i < 5; i++) {
                if (stopRequested()) {
                    log.info("停止指令已触发，结束查找聊天按钮 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                    closeOwnedPage(detailPage);
                    return "已跳过";
                }
                if (chatBtn.count() > 0) {
                    String buttonText = chatBtn.first().textContent();
                    if (buttonText != null && buttonText.contains("继续沟通")
                            && !Boolean.TRUE.equals(config.getAllowRepeatChat())) {
                        log.info("已关闭重复沟通，跳过已沟通Boss | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                        closeOwnedPage(detailPage);
                        return "已沟通跳过";
                    }
                    if (buttonText != null && (buttonText.contains("立即沟通")
                            || (buttonText.contains("继续沟通") && Boolean.TRUE.equals(config.getAllowRepeatChat())))) {
                        foundChatBtn = true;
                        repeatChat = buttonText.contains("继续沟通");
                        break;
                    }
                }
                sleepWithStopCheck(1_000);
            }
            if (!foundChatBtn) {
                log.warn("未找到可用的沟通按钮，跳过岗位: {}", job.getJobName());
                // 关闭详情页
                closeOwnedPage(detailPage);
                return "已跳过";
            }
            if (dryRun) {
                // 只评估模式：确认岗位可以发起沟通即结束，不点击沟通、不发送任何内容
                log.info("单次测试（只评估）：岗位通过判定且可发起沟通，未发送 | 公司：{} | 岗位：{} | 招呼语：{}",
                        job.getCompanyName(), job.getJobName(), greeting);
                closeOwnedPage(detailPage);
                return "测试通过未发送";
            }
            if (repeatChat) {
                repeatMessage = config.randomRepeatChatMessage();
                if (repeatMessage == null) {
                    log.info("重复沟通消息列表为空，跳过岗位：{}", job.getJobName());
                    closeOwnedPage(detailPage);
                    return "已跳过";
                }
            }
            BossChatNavigator.Chat chat = navigator.open(chatBtn.first(), repeatChat,
                    Boolean.TRUE.equals(config.getAllowRepeatChat()), this::stopRequested, 20_000);
            if (chat == null) return "已跳过";
            if (stopRequested() || checkRiskByPageText(chat.page())) return "已跳过";
            detailPage = chat.page();
            repeatChat = chat.repeat();
            // 重复沟通状态由岗位按钮决定，通知框本身不代表旧会话。
            if (repeatChat && repeatMessage == null) {
                repeatMessage = config.randomRepeatChatMessage();
                if (repeatMessage == null) {
                    log.info("重复沟通消息列表为空，跳过岗位：{}", job.getJobName());
                    return "已跳过";
                }
            }
            Locator inputLocator = chat.input();

            // 5. 招呼语由匹配闸门给出（已通过规则与 AI 复核及禁用词校验）
            String message = repeatChat ? repeatMessage : greeting;
            if (!isValidString(message)) {
                log.warn("招呼语为空，不发送 | 岗位：{}", job.getJobName());
                closeOwnedPage(detailPage);
                return "已跳过";
            }

            // 6. 输入打招呼语
            if (stopRequested()) return "已跳过";
            Locator input = inputLocator.first();
            input.click();
            Object tagObj = input.evaluate("el => el.tagName.toLowerCase()");
            if (tagObj instanceof String && ((String) tagObj).equals("textarea")) {
                input.fill(message);
            } else {
                // 对 contenteditable 节点写入文本并派发 input 事件
                input.evaluate("(el, msg) => { el.innerText = msg; el.dispatchEvent(new Event('input')); }", message);
            }

            // 7. 点击发送按钮（div.send-message 或 button.btn-send）
            Locator sendText = detailPage.locator("div.send-message, button[type='send'].btn-send, button.btn-send");
            boolean sendSuccess = false;
            if (stopRequested()) return "已跳过";
            if (sendText.count() > 0) {
                sendText.first().click();
                PlaywrightUtil.sleep(1);
                sendSuccess = true;
            } else {
                log.warn("未找到发送按钮，自动跳过！岗位：{}", job.getJobName());
            }

            // 8. 发送图片简历（可选）
            boolean imgResume = false;
            if (sendSuccess && Boolean.TRUE.equals(config.getSendImgResume())) {
                imgResume = sendImageResume(detailPage);
            }

            log.info("投递完成 | 公司：{} | 岗位：{} | 薪资：{} | 招呼语：{} | 图片简历：{}", job.getCompanyName(), job.getJobName(), job.getSalary(), message, imgResume ? "已发送" : "未发送");

            // 9. 关闭新打开的详情页
            closeOwnedPage(detailPage);
            humanPause(600, 1400);

            // 10. 更新数据库投递状态 & 成功投递加入结果
            if (sendSuccess) {
                // 从详情链接提取 encrypt_id，并映射到 encrypt_user_id
                String encryptId = extractEncryptId(detailUrl);
                String encryptUserId = encryptId != null ? encryptIdToUserId.get(encryptId) : null;
                if (encryptId != null && encryptUserId != null) {
                    try {
            bossService.updateDeliveryStatus(encryptId, encryptUserId, "已投递");
                        log.info("投递成功 | 公司：{} | 岗位：{} | encryptId：{} | encryptUserId：{}", job.getCompanyName(), job.getJobName(), encryptId, encryptUserId);
                    } catch (Exception e) {
                        log.warn("更新投递状态为已投递失败：{}", e.getMessage());
                    }
                } else {
                    log.debug("未能找到 encryptId/encryptUserId 用于更新投递状态，detailUrl: {}", detailUrl);
                }
                resultList.add(job);
            } else {
                // 若发生发送失败，也进行状态更新
                String encryptId = extractEncryptId(detailUrl);
                String encryptUserId = encryptId != null ? encryptIdToUserId.get(encryptId) : null;
                if (encryptId != null && encryptUserId != null) {
                    try {
            bossService.updateDeliveryStatus(encryptId, encryptUserId, "投递失败");
                        log.warn("投递失败 | 公司：{} | 岗位：{} | encryptId：{} | encryptUserId：{}", job.getCompanyName(), job.getJobName(), encryptId, encryptUserId);
                    } catch (Exception e) {
                        log.warn("更新投递状态为投递失败异常：{}", e.getMessage());
                    }
                }
            }
            if (!sendSuccess) return "发送失败";
            return imgResume || !Boolean.TRUE.equals(config.getSendImgResume()) ? "已发送" : "已发送（图片未发全）";
        }
    }

    

    /**
     * 关闭程序自己打开的详情页 / 聊天页；风控已触发或页面停在风控页时不关闭，留给人工处理。
     *
     * @param target Page，可空，要关闭的标签页
     */
    private void closeOwnedPage(Page target) {
        if (target == null) return;
        try {
            if (target.isClosed()) return;
            if (riskType != null || BossRiskDetector.classifyUrl(target.url()) != null) {
                log.info("风控已触发或页面为风控页，保留该标签页：{}", target.url());
                return;
            }
            target.close();
        } catch (Exception ignore) {
        }
    }

    /**
     * 注册页面响应监听：拦截 /wapi/zpgeek/job/detail.json 请求并解析写库
     */
    private void attachJobDetailResponseListener() {
        if (page == null) return;
        page.onResponse(resp -> {
            try {
                String url = resp.url();
                if (url == null) return;
                // 仅处理 Boss 岗位详情接口（GET）
                if (url.contains("/wapi/zpgeek/job/detail.json") &&
                        "GET".equalsIgnoreCase(resp.request().method())) {
                    String body = null;
                    try {
                        body = resp.text();
                    } catch (Throwable ignore) {
                        // 某些情况下可能拿不到文本，忽略
                    }
                    if (body == null || body.isEmpty()) return;

                    // 保存原始 JSON 到 target/job.txt
                    appendRawJson(body);

                    // 仅记录映射与原始 JSON；入库逻辑已移动到点击卡片时
                    JSONObject root = new JSONObject(body);
                    JSONObject zpData = root.optJSONObject("zpData");
                    if (zpData == null) return;
                    JSONObject jobInfo = zpData.optJSONObject("jobInfo");
                    if (jobInfo == null) return;
                    String encryptId = jobInfo.optString("encryptId", null);
                    String encryptUserId = jobInfo.optString("encryptUserId", null);
                    if (encryptId != null && encryptUserId != null) {
                        encryptIdToUserId.put(encryptId, encryptUserId);
                    }
                }
            } catch (Throwable e) {
                log.debug("监听岗位详情响应处理异常：{}", e.getMessage());
            }
        });
    }


    /**
     * 追加保存原始 JSON 到 target/job.txt
     */
    private void appendRawJson(String body) {
        try {
            java.io.File dir = new java.io.File("target");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "job.txt");
            try (java.io.FileWriter fw = new java.io.FileWriter(file, true)) {
                fw.write(body);
                fw.write(System.lineSeparator());
                fw.write("\n");
            }
        } catch (Exception e) {
            log.debug("写入 target/job.txt 失败：{}", e.getMessage());
        }
    }

    /**
     * 从详情页 URL 中提取 encrypt_id
     */
    private String extractEncryptId(String detailUrl) {
        try {
            if (detailUrl == null) return null;
            String key = "/job_detail/";
            int idx = detailUrl.indexOf(key);
            if (idx < 0) return null;
            int start = idx + key.length();
            int end = detailUrl.indexOf(".html", start);
            if (end < 0) end = detailUrl.length();
            return detailUrl.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValidString(String str) {
        return str != null && !str.isEmpty();
    }

    /**
     * 打招呼语发送成功后，按上传顺序逐张发送图片简历；某张失败时停止发送后续图片。
     *
     * @param page Page，必填，无默认值；当前岗位详情/聊天页面
     * @return boolean，全部图片都确认发送成功时为 true；未上传图片、找不到上传控件或任一张发送失败时为 false
     */
    private boolean sendImageResume(Page page) {
        try {
            List<java.nio.file.Path> imagePaths = bossResumeImageService.getCurrentImagePaths();
            if (imagePaths.isEmpty()) {
                log.warn("未上传图片简历，跳过图片发送；请先在 Boss 管理页面上传");
                return false;
            }

            int total = imagePaths.size();
            for (int i = 0; i < total; i++) {
                java.nio.file.Path imagePath = imagePaths.get(i);
                String label = (i + 1) + "/" + total + " " + imagePath.getFileName();

                // 第 1 张可能需要从简化打招呼层切到完整聊天页；之后完整聊天页已就绪，只需重新获取控件。
                Locator imageInput = i == 0 ? prepareImageUploadInput(page) : waitForImageUploadInput(page, 10);
                if (imageInput == null && i > 0) {
                    // 上一张发送后 Boss 会重新渲染聊天区，上传控件可能暂时消失；重新进入完整聊天页再找一次
                    log.info("第 {} 张前未找到图片上传控件，重新进入完整聊天页面后重试", i + 1);
                    imageInput = prepareImageUploadInput(page);
                    if (imageInput == null && safePageUrl(page).contains("/web/geek/chat")) {
                        // 已在完整聊天页但控件没挂载：刷新当前会话后再等一次（刷新不会重复发送已发出的图片）
                        try {
                            page.reload(new Page.ReloadOptions().setTimeout(20_000));
                        } catch (Throwable reloadError) {
                            log.debug("刷新聊天页时出现异常，继续检查上传控件：{}", reloadError.getMessage());
                        }
                        imageInput = waitForImageUploadInput(page, 15);
                        if (imageInput != null) {
                            log.info("刷新聊天页后已找到图片上传控件");
                        }
                    }
                }
                if (imageInput == null) {
                    log.error("发送图片简历失败：未找到图片上传控件，已发送 {}/{} 张，页面：{}",
                            i, total, safePageUrl(page));
                    return false;
                }

                // 必须在完整聊天页就绪后再记录基线；否则重新进入聊天时加载出的历史图片会被误判为本次发送。
                JSONObject beforeState = readOwnImageMessageState(page);
                int imageMessageCountBefore = beforeState.optInt("imageCount", 0);

                imageInput.setInputFiles(imagePath);
                log.info("已选择图片简历 {}，等待 Boss 确认发送", label);

                // 必须等到聊天记录中出现新的本人图片消息，并且上传/发送状态已经结束。
                // 这一步完成前不能关闭详情页或选择下一张，否则大图片或网络较慢时上传会被中断。
                if (!waitForImageMessageSent(page, imageMessageCountBefore, label)) {
                    log.error("图片简历 {} 发送失败，停止发送后续图片", label);
                    return false;
                }
                if (i < total - 1) {
                    // 连续发图之间留出间隔，降低触发平台频率限制的概率。
                    PlaywrightUtil.sleep(2);
                }
            }
            log.info("图片简历全部发送完成，共 {} 张", total);
            return true;
        } catch (Throwable e) {
            log.error("发送图片简历失败：{}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取可用的图片上传控件。
     * <p>
     * Boss 第一次“立即沟通”有时只打开简化打招呼层，发完文字后该层不会提供图片按钮。
     * 此时关闭简化层并重新点击“继续沟通”，等待完整聊天 DOM；不等待 URL，避免导航期间
     * Patchright 持有旧 frame 而出现 Object doesn't exist: frame@...。
     */
    private Locator prepareImageUploadInput(Page page) {
        Locator currentInput = waitForImageUploadInput(page, 2);
        if (currentInput != null) {
            log.info("当前聊天层已找到图片上传控件");
            return currentInput;
        }

        log.info("当前打招呼层没有图片上传控件，尝试重新进入完整聊天页面");
        closeLightweightChatPanel(page);

        Locator chatButton = findVisibleChatButton(page);
        if (chatButton == null) {
            log.warn("重新进入完整聊天失败：未找到【继续沟通/立即沟通】按钮，页面：{}", safePageUrl(page));
            return null;
        }

        try {
            String buttonText = Optional.ofNullable(chatButton.textContent()).orElse("").trim();
            chatButton.click(new Locator.ClickOptions().setTimeout(10_000));
            log.info("已点击【{}】，等待完整聊天页面的图片控件", buttonText.isEmpty() ? "继续沟通" : buttonText);
        } catch (Throwable clickError) {
            // 点击可能已经触发导航，只是旧 frame 在回包时被销毁；继续通过新 Locator 探测 DOM。
            log.debug("点击沟通按钮时页面发生切换，将继续检查新聊天页面：{}", clickError.getMessage());
        }

        Locator reopenedInput = waitForImageUploadInput(page, 15);
        if (reopenedInput != null) {
            log.info("重新进入完整聊天页面后已找到图片上传控件");
        }
        return reopenedInput;
    }

    private void closeLightweightChatPanel(Page page) {
        try {
            Locator closeButtons = page.locator("i.icon-close");
            int count = closeButtons.count();
            for (int i = 0; i < count; i++) {
                Locator closeButton = closeButtons.nth(i);
                if (closeButton.isVisible()) {
                    closeButton.click(new Locator.ClickOptions().setTimeout(5_000));
                    PlaywrightUtil.sleep(1);
                    log.debug("已关闭不含图片控件的简化打招呼层");
                    return;
                }
            }
        } catch (Throwable closeError) {
            log.debug("关闭简化打招呼层失败，将直接尝试重新进入聊天：{}", closeError.getMessage());
        }
    }

    private Locator findVisibleChatButton(Page page) {
        try {
            Locator buttons = page.locator("a.btn-startchat, a.op-btn-chat");
            int count = buttons.count();
            for (int i = 0; i < count; i++) {
                Locator button = buttons.nth(i);
                if (button.isVisible()) {
                    return button;
                }
            }
        } catch (Throwable findError) {
            log.debug("查找沟通按钮失败：{}", findError.getMessage());
        }
        return null;
    }

    /** 每轮都重新创建 Locator，以适应点击沟通按钮后发生的主 frame 替换。 */
    private Locator waitForImageUploadInput(Page page, int timeoutSeconds) {
        for (int second = 0; second < timeoutSeconds; second++) {
            if (page.isClosed()) {
                return null;
            }
            Locator input = findAttachedImageUploadInput(page);
            if (input != null) {
                return input;
            }
            PlaywrightUtil.sleep(1);
        }
        return null;
    }

    private Locator findAttachedImageUploadInput(Page page) {
        List<String> selectors = List.of(
                "div.btn-sendimg[aria-label='发送图片'] input[type='file']",
                "div[aria-label='发送图片'].btn-sendimg input[type='file']",
                "[aria-label='发送图片'] input[type='file']",
                "input[type='file'][accept*='image/gif'][accept*='image/jpeg']"
        );

        try {
            // 完整聊天页在不同版本中可能位于主 frame 或子 frame，因此逐个检查。
            for (Frame frame : page.frames()) {
                if (frame.isDetached()) {
                    continue;
                }
                for (String selector : selectors) {
                    try {
                        Locator candidate = frame.locator(selector).first();
                        if (candidate.count() > 0) {
                            // 文件输入框通常隐藏，只需确认已挂载，不能要求 visible。
                            candidate.waitFor(new Locator.WaitForOptions()
                                    .setState(WaitForSelectorState.ATTACHED)
                                    .setTimeout(1_000));
                            return candidate;
                        }
                    } catch (Throwable staleFrame) {
                        // 页面切换中的旧 frame 会失效，下一轮会重新获取 page.frames()。
                    }
                }
            }
        } catch (Throwable frameListError) {
            log.debug("检查图片上传控件时页面正在切换：{}", frameListError.getMessage());
        }
        return null;
    }

    private String safePageUrl(Page page) {
        try {
            return page.url();
        } catch (Throwable ignore) {
            return "无法读取";
        }
    }

    /**
     * 等待图片真正进入本人消息列表，而不是仅确认文件已选择。
     */
    private boolean waitForImageMessageSent(Page page, int imageMessageCountBefore, String filename) {
        JSONObject lastState = null;
        int stableSeconds = 0;
        for (int second = 1; second <= IMAGE_SEND_CONFIRM_TIMEOUT_SECONDS; second++) {
            if (page.isClosed()) {
                log.error("图片简历发送确认失败：聊天页面已关闭，文件：{}", filename);
                return false;
            }

            try {
                lastState = readOwnImageMessageState(page);
                int currentCount = lastState.optInt("imageCount", 0);
                boolean failed = lastState.optBoolean("failed", false);
                boolean sending = lastState.optBoolean("sending", false);
                String status = lastState.optString("status", "");

                if (currentCount > imageMessageCountBefore && failed) {
                    log.error("图片简历发送失败，聊天消息显示失败状态，文件：{}，状态：{}", filename, status);
                    return false;
                }
                if (currentCount > imageMessageCountBefore && !sending) {
                    stableSeconds++;
                    // 新图片消息连续稳定三秒后再关闭页面，避免刚插入的临时消息仍在后台上传。
                    if (stableSeconds >= 3) {
                        log.info("图片简历已出现在本人聊天记录中，确认发送完成，文件：{}，状态：{}",
                                filename, status);
                        return true;
                    }
                } else {
                    stableSeconds = 0;
                }
            } catch (Throwable probeError) {
                stableSeconds = 0;
                log.debug("等待图片简历发送确认时读取聊天状态失败（第{}秒）：{}", second, probeError.getMessage());
            }
            PlaywrightUtil.sleep(1);
        }

        log.error("图片简历已选择，但{}秒内没有确认出现在本人聊天记录中，按发送失败处理。文件：{}，最后状态：{}",
                IMAGE_SEND_CONFIRM_TIMEOUT_SECONDS,
                filename,
                lastState != null ? lastState.toString() : "无法读取");
        return false;
    }

    /**
     * 读取 Boss 聊天区本人图片消息的数量及最后一条图片消息状态。
     * item-myself 是本人消息，item-friend 是招聘者消息；只检查 message-content，避免把头像误判为图片消息。
     */
    private JSONObject readOwnImageMessageState(Page page) {
        String probeScript = """
                () => {
                  const root = document.querySelector('.chat-message .im-list')
                    || document.querySelector('.chat-record');
                  if (!root) {
                    return JSON.stringify({ imageCount: 0, sending: false, failed: false, status: '', rootFound: false });
                  }

                  const ownMessages = Array.from(root.querySelectorAll('li.message-item.item-myself'));
                  const imageMessages = ownMessages.filter(item => {
                    const content = item.querySelector('.message-content');
                    if (!content) return false;
                    return Boolean(content.querySelector(
                      'img, [class*="image-content"], [class*="message-image"], [class*="message-img"], ' +
                      '[class*="picture"], [style*="background-image"]'
                    ));
                  });

                  const last = imageMessages.length > 0 ? imageMessages[imageMessages.length - 1] : null;
                  if (!last) {
                    return JSON.stringify({ imageCount: 0, sending: false, failed: false, status: '', rootFound: true });
                  }

                  const statusEl = last.querySelector('.message-status');
                  const status = statusEl ? (statusEl.textContent || '').trim() : '';
                  const classText = [last, ...last.querySelectorAll('*')]
                    .map(el => typeof el.className === 'string' ? el.className : '')
                    .join(' ')
                    .toLowerCase();
                  const messageText = (last.textContent || '').trim();
                  const sending = /uploading|sending|upload-progress|send-progress/.test(classText)
                    || /上传中|发送中/.test(messageText + status);
                  const failed = /upload-fail|upload-error|send-fail|send-error|message-fail/.test(classText)
                    || /上传失败|发送失败|重新发送|点击重试/.test(messageText + status);

                  return JSON.stringify({
                    imageCount: imageMessages.length,
                    sending,
                    failed,
                    status,
                    rootFound: true
                  });
                }
                """;

        try {
            for (Frame frame : page.frames()) {
                if (frame.isDetached()) {
                    continue;
                }
                try {
                    Object stateJson = frame.evaluate(probeScript);
                    JSONObject state = new JSONObject(String.valueOf(stateJson));
                    if (state.optBoolean("rootFound", false)) {
                        return state;
                    }
                } catch (Throwable staleFrame) {
                    // frame 正在替换时忽略，本轮按未找到聊天记录处理。
                }
            }
        } catch (Throwable frameListError) {
            log.debug("读取聊天图片状态时页面正在切换：{}", frameListError.getMessage());
        }
        return new JSONObject()
                .put("imageCount", 0)
                .put("sending", false)
                .put("failed", false)
                .put("status", "")
                .put("rootFound", false);
    }

    /**
     * 检查岗位薪资是否符合预期
     *
     * @return boolean
     * true 不符合预期
     * false 符合预期
     * 期望的最低薪资如果比岗位最高薪资还小，则不符合（薪资给的太少）
     * 期望的最高薪资如果比岗位最低薪资还小，则不符合(要求太高满足不了)
     */
    private boolean isSalaryNotExpected(String salary) {
        try {
            // 1. 如果没有期望薪资范围，直接返回 false，表示"薪资并非不符合预期"
            List<Integer> expectedSalary = config.getExpectedSalary();
            if (!hasExpectedSalary(expectedSalary)) {
                return false;
            }

            // 2. 清理薪资文本（比如去掉 "·15薪"）
            salary = removeYearBonusText(salary);

            // 3. 如果薪资格式不符合预期（如缺少 "K" / "k"），直接返回 true，表示"薪资不符合预期"
            if (!isSalaryInExpectedFormat(salary)) {
                return true;
            }

            // 4. 进一步清理薪资文本，比如去除 "K"、"k"、"·" 等
            salary = cleanSalaryText(salary);

            // 5. 判断是 "月薪" 还是 "日薪"
            String jobType = detectJobType(salary);
            salary = removeDayUnitIfNeeded(salary); // 如果是按天，则去除 "元/天"

            // 6. 解析薪资范围并检查是否超出预期
            Integer[] jobSalaryRange = parseSalaryRange(salary);
            return isSalaryOutOfRange(jobSalaryRange,
                    getMinimumSalary(expectedSalary),
                    getMaximumSalary(expectedSalary),
                    jobType);

        } catch (Exception e) {
            log.error("岗位薪资获取异常！薪资文本【{}】,异常信息【{}】", salary, e.getMessage(), e);
            // 出错时，您可根据业务需求决定返回 true 或 false
            // 这里假设出错时无法判断，视为不满足预期 => 返回 true
            return true;
        }
    }

    /**
     * 是否存在有效的期望薪资范围
     */
    private boolean hasExpectedSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty();
    }

    /**
     * 去掉年终奖信息，如 "·15薪"、"·13薪"。
     */
    private String removeYearBonusText(String salary) {
        if (salary.contains("薪")) {
            // 使用正则去除 "·任意数字薪"
            return salary.replaceAll("·\\d+薪", "");
        }
        return salary;
    }

    /**
     * 判断是否是按天计薪，如发现 "元/天" 则认为是日薪
     */
    private String detectJobType(String salary) {
        if (salary.contains("元/天")) {
            return "day";
        }
        return "mouth";
    }

    /**
     * 如果是日薪，则去除 "元/天"
     */
    private String removeDayUnitIfNeeded(String salary) {
        if (salary.contains("元/天")) {
            return salary.replaceAll("元/天", "");
        }
        return salary;
    }

    private Integer getMinimumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty() ? expectedSalary.get(0) : null;
    }

    private Integer getMaximumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && expectedSalary.size() > 1 ? expectedSalary.get(1) : null;
    }

    private boolean isSalaryInExpectedFormat(String salaryText) {
        return salaryText.contains("K") || salaryText.contains("k") || salaryText.contains("元/天");
    }

    private String cleanSalaryText(String salaryText) {
        salaryText = salaryText.replace("K", "").replace("k", "");
        int dotIndex = salaryText.indexOf('·');
        if (dotIndex != -1) {
            salaryText = salaryText.substring(0, dotIndex);
        }
        return salaryText;
    }

    private boolean isSalaryOutOfRange(Integer[] jobSalary, Integer miniSalary, Integer maxSalary,
                                       String jobType) {
        if (jobSalary == null) {
            return true;
        }
        if (miniSalary == null) {
            return false;
        }
        if (Objects.equals("day", jobType)) {
            // 期望薪资转为平均每日的工资
            maxSalary = BigDecimal.valueOf(maxSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
            miniSalary = BigDecimal.valueOf(miniSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
        }
        // 如果职位薪资下限低于期望的最低薪资，返回不符合
        if (jobSalary[1] < miniSalary) {
            return true;
        }
        // 如果职位薪资上限高于期望的最高薪资，返回不符合
        return maxSalary != null && jobSalary[0] > maxSalary;
    }

    public boolean containsDeadStatus(String activeTimeText, List<String> deadStatus) {
        for (String status : deadStatus) {
            if (activeTimeText.contains(status)) {
                return true;// 一旦找到包含的值，立即返回 true
            }
        }
        return false;// 如果没有找到，返回 false
    }

    private Integer[] parseSalaryRange(String salaryText) {
        try {
            return Arrays.stream(salaryText.split("-")).map(s -> s.replaceAll("[^0-9]", "")) // 去除非数字字符
                    .map(Integer::parseInt) // 转换为Integer
                    .toArray(Integer[]::new); // 转换为Integer数组
        } catch (Exception e) {
            log.error("薪资解析异常！{}", e.getMessage(), e);
        }
        return null;
    }

    /** wait_time 没配或配得不合法时用的秒数 */
    private static final int DEFAULT_WAIT_TIME_SECONDS = 10;

    /**
     * 取配置里的 wait_time（秒），非法值一律退回默认值。
     */
    private int resolveWaitTimeSeconds() {
        try {
            String raw = config == null ? null : config.getWaitTime();
            if (raw != null && !raw.isBlank()) {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed > 0) {
                    return parsed;
                }
            }
        } catch (NumberFormatException ignored) {
            // 配置里塞了非数字，用默认值
        }
        return DEFAULT_WAIT_TIME_SECONDS;
    }

    /**
     * 每处理完一个岗位后的停顿。
     * <p>
     * 上一轮实测 7 分钟连刷 297 个岗位详情，直接把 Boss 风控触发了，
     * 后续关键词全部被弹到安全验证页。这里按 wait_time 降速：
     * 正常模式在 [wait_time/2, wait_time] 之间随机，避免固定节奏本身成为特征；
     * 调试模式固定用最大值 wait_time，方便观察。
     */
    /**
     * 岗位之间的停顿：以 wait_time 为基准在 [0.5, 1.5] 倍之间偏短随机取值，
     * 约 8% 的概率额外多停 15~45 秒，模拟看手机、切走一会儿；调试模式下固定为 wait_time。
     * 入参：无。出参：无。
     */
    private void pauseBetweenJobs() {
        int waitTime = resolveWaitTimeSeconds();
        if (Boolean.TRUE.equals(config.getDebugger())) {
            sleepWithStopCheck(waitTime * 1000L);
            return;
        }
        long base = waitTime * 1000L;
        humanPause(base / 2, base * 3 / 2);
        if (ThreadLocalRandom.current().nextDouble() < 0.08) {
            log.debug("模拟短暂离开，额外停顿");
            humanPause(15_000, 45_000);
        }
    }


    private boolean isLoginRequired() {
        try {
            Locator buttonLocator = page.locator(LOGIN_BTNS);
            if (buttonLocator.count() > 0 && buttonLocator.textContent().contains("登录")) {
                return true;
            }
        } catch (Exception e) {
            try {
                page.locator(PAGE_HEADER).waitFor();
                Locator errorLoginLocator = page.locator(ERROR_PAGE_LOGIN);
                if (errorLoginLocator.count() > 0) {
                    errorLoginLocator.click();
                }
                return true;
            } catch (Exception ex) {
                log.info("没有出现403访问异常");
            }
            log.info("cookie有效，已登录...");
            return false;
        }
        return false;
    }

}
