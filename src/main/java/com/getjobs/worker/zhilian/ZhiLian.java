package com.getjobs.worker.zhilian;

import com.getjobs.application.entity.ZhilianJobDataEntity;
import com.getjobs.application.service.BossResumeImageService;
import com.getjobs.application.service.JobMatchService;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.worker.match.JobCandidate;
import com.getjobs.worker.match.MatchDecision;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 智联招聘自动投递（适配 2026-11 起的新版搜索页 www.zhaopin.com/jobs/）。
 * <p>
 * 页面结构：左侧岗位卡片列表 .job-card（每次加载 20 个，滚动到底继续加载），点卡片后右侧 .job-detail-panel
 * 显示岗位详情、「立即投递」按钮与「查看更多职位信息」链接（链接中含岗位编号）。
 * <p>
 * 每个岗位的流程：点卡片 → 读右侧详情 → 近期已处理跳过 → 匹配闸门（名称规则 + AI 复核）→
 * 打开详情页（优先 jobLink 直开）→ 点「立即投递」（平台同时发送在线简历与账号默认招呼语）→
 * 关闭结果框。不进聊天、不补发招呼语与图片简历（图片含二维码会被智联拦截）。
 * 列表页始终不离开当前页面。
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class ZhiLian {

    @Setter
    private Page page;

    @Setter
    private ZhilianConfig config;

    @Setter
    private ProgressCallback progressCallback;

    @Setter
    private Supplier<Boolean> shouldStopCallback;

    /** 本次任务最多实际投递几个岗位，0 表示不限；单次测试时为 1 */
    @Setter
    private int maxDeliveries = 0;
    /** true 表示只评估不投递：走完判定并确认「立即投递」按钮可用，但不点击、不发任何消息 */
    @Setter
    private boolean dryRun = false;
    /** 本次任务最多评估几个岗位（进入 AI 复核的岗位数），0 表示不限；用于限制单次测试时长 */
    @Setter
    private int maxEvaluations = 0;
    /** 过滤判定的有效起点：在此之后被过滤的岗位视为近期已处理；由任务服务设置 */
    @Setter
    private LocalDateTime processedCutoff = LocalDateTime.now().minusDays(3);

    private final ZhilianService zhilianService;
    private final JobMatchService jobMatchService;
    private final BossResumeImageService bossResumeImageService;

    /** 新版搜索页地址 */
    private static final String SEARCH_URL = "https://www.zhaopin.com/jobs/";
    /** 智联「全国」城市代码 */
    private static final String NATIONWIDE_CITY_CODE = "489";
    /** 点开岗位后的停留范围（毫秒） */
    private static final long CARD_VIEW_PAUSE_MIN = 4_000L, CARD_VIEW_PAUSE_MAX = 9_000L;
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
    /** 连续 AI 失败上限 */
    private static final int MAX_CONSECUTIVE_AI_ERRORS = 3;
    /** 两次投递之间的基准等待秒数 */
    private static final int WAIT_TIME_SECONDS = 10;
    /** 等待图片消息出现在聊天记录中的最长秒数 */
    private static final int IMAGE_SEND_CONFIRM_TIMEOUT_SECONDS = 45;

    /** 卡片选择器 */
    private static final String CARD = ".job-card";
    /** 右侧详情区「查看更多职位信息」链接，href 含 jobdetail/{岗位编号}.htm */
    private static final String VIEW_MORE = ".job-detail-panel a.job-detail-view-more";
    /** 「立即投递」按钮（列表右侧详情区与独立详情页通用） */
    private static final String APPLY_BTN = ".job-detail-summary__apply, .job-detail-summary button.a-button";
    /** 独立详情页上可能用到的投递按钮选择器（版式不同时兜底） */
    private static final String[] APPLY_SELECTORS = {
            ".job-detail-summary__apply",
            ".job-detail-summary button.a-button",
            "button:has-text('立即投递')",
            "a:has-text('立即投递')"
    };
    /** 聊天输入框 */
    private static final String CHAT_INPUT = "textarea.im-sender__input";
    /** 聊天发送按钮 */
    private static final String CHAT_SEND = "button.im-sender__send-btn";
    /** 聊天图片上传控件 */
    private static final String CHAT_IMAGE_INPUT = "input.im-sender__file";

    /** 本次任务的逐岗位判定记录，供管理页面展示 */
    private final List<Map<String, Object>> evaluations = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** 已实际投递的岗位数（只评估模式下为找到的可投递岗位数） */
    private volatile int deliveredCount = 0;
    /** 已进入 AI 复核的岗位数 */
    private volatile int evaluatedCount = 0;
    /** 本次任务遇到的新岗位数 */
    private volatile int newJobCount = 0;
    /** 本次任务跳过的近期已处理岗位数 */
    private volatile int skippedOldCount = 0;
    /** 本次任务按卡片名称过滤（未点开）的岗位次数 */
    private volatile int titleFilteredCount = 0;
    /** 连续因 AI 不可用而未能判定的岗位数 */
    private int consecutiveAiErrors = 0;
    /** 因 AI 不可用、安全验证等原因中止时的原因；为 null 表示未中止 */
    private volatile String abortReason = null;
    /** 达到每日上限或平台投递上限时的提示；为 null 表示未达到 */
    private volatile String limitNotice = null;

    /**
     * 进度回调接口。
     */
    @FunctionalInterface
    public interface ProgressCallback {
        /**
         * 输出进度。
         *
         * @param message String，必填，进度文本
         * @param current Integer，可空，当前序号
         * @param total   Integer，可空，总数
         */
        void accept(String message, Integer current, Integer total);
    }

    /** 右侧详情区读到的岗位信息 */
    private record PanelJob(String jobId, String jobLink, String title, String company, String salary,
                            String location, String description, String applyText) {
    }

    /**
     * 准备工作：清空本次任务的计数与记录。
     * 入参：无。出参：无。
     */
    public void prepare() {
        evaluations.clear();
        deliveredCount = 0;
        evaluatedCount = 0;
        newJobCount = 0;
        skippedOldCount = 0;
        titleFilteredCount = 0;
        consecutiveAiErrors = 0;
        abortReason = null;
        limitNotice = null;
    }

    /**
     * 执行投递任务：按关键词逐个搜索并处理。
     * 入参：无。
     *
     * @return int，本次实际投递的岗位数
     */
    public int execute() {
        long start = System.currentTimeMillis();
        boolean firstKeyword = true;
        for (String keyword : config.getKeywords()) {
            if (!firstKeyword) restWithStopCheck(KEYWORD_REST_MIN, KEYWORD_REST_MAX);
            firstKeyword = false;
            if (stopRequested()) break;
            if (page.isClosed()) {
                abortReason = "智联标签页已被关闭，任务已停止，请重新点击开始投递（会自动重新打开标签页）";
                log.warn(abortReason);
                break;
            }
            try {
                deliverByKeyword(keyword);
            } catch (Exception e) {
                if (page.isClosed()) {
                    abortReason = "智联标签页在投递过程中被关闭，任务已停止，请重新点击开始投递（会自动重新打开标签页）";
                    log.warn(abortReason);
                    break;
                }
                log.error("智联关键词【{}】处理失败，跳过：{}", keyword, e.getMessage(), e);
                sendProgress("关键词[" + keyword + "]失败已跳过：" + e.getMessage(), null, null);
            }
        }
        log.info("智联投递结束，投递 {} 个，新岗位 {} 个，跳过近期已处理 {} 个，用时 {} 秒",
                deliveredCount, newJobCount, skippedOldCount, (System.currentTimeMillis() - start) / 1000);
        return deliveredCount;
    }

    /**
     * 处理一个关键词：打开搜索结果，逐个查看全部岗位直到列表末尾，是否投递由匹配闸门决定。
     *
     * @param keyword String，必填，搜索关键词
     */
    private void deliverByKeyword(String keyword) {
        sendProgress("正在搜索关键词：" + keyword, null, null);
        String url = SEARCH_URL + "?jl=" + resolveCityCode() + "&kw=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        // 工作性质筛选：et=1 为「兼职/临时」
        if (!Boolean.FALSE.equals(config.getOnlyPartTime())) url += "&et=1";
        page.navigate(url, new Page.NavigateOptions().setTimeout(30_000));
        if (checkSecurityVerify()) return;
        try {
            page.waitForSelector(CARD, new Page.WaitForSelectorOptions().setTimeout(15_000));
        } catch (Exception e) {
            if (checkSecurityVerify()) return;
            log.warn("【{}】等待岗位列表超时，跳过该关键词，当前页面：{}", keyword, safeUrl(page));
            return;
        }
        humanPause(1500, 3000);

        for (int i = 0; ; i++) {
            if (stopRequested()) return;
            if (checkSecurityVerify()) return;
            if (!ensureCardLoaded(i)) {
                log.info("【{}】列表只有 {} 个岗位，处理完毕", keyword, i);
                return;
            }
            if (isCardRecentlyProcessed(i)) {
                skippedOldCount++;
                continue;
            }
            if (isRejectedByCardTitle(i)) {
                titleFilteredCount++;
                continue;
            }
            PanelJob job = openCard(i);
            if (++viewedSinceRest >= REST_EVERY_VIEWED) {
                viewedSinceRest = 0;
                log.info("已点开 {} 个岗位，批次休息", REST_EVERY_VIEWED);
                restWithStopCheck(BATCH_REST_MIN, BATCH_REST_MAX);
                if (stopRequested()) return;
            }
            if (job == null) {
                consecutiveMissingDetail++;
                log.info("【{}】第 {} 个岗位详情未加载，跳过（连续 {} 个）", keyword, i + 1, consecutiveMissingDetail);
                if (checkSecurityVerify()) return;
                if (consecutiveMissingDetail >= MAX_MISSING_DETAIL) {
                    abortReason = "连续 " + consecutiveMissingDetail + " 个岗位详情未加载，可能触发平台校验，任务已自动停止，请在 noVNC 查看智联页面";
                    log.warn(abortReason);
                    sendProgress(abortReason, null, null);
                    return;
                }
                continue;
            }
            consecutiveMissingDetail = 0;
            if (job.jobId() != null && zhilianService.isRecentlyProcessed(job.jobId(), processedCutoff)) {
                skippedOldCount++;
                continue;
            }
            newJobCount++;
            saveJob(job);
            handleJob(keyword, job, i);
            if (stopRequested()) return;
        }
    }

    /**
     * 取搜索用的城市代码。智联新版搜索页不认「不限」对应的 0，会退回按 IP 定位的城市站，
     * 因此未设置或为 0 时按全国（489）搜索。
     * 入参：无。
     *
     * @return String，城市代码，不为空
     */
    private String resolveCityCode() {
        String code = config.getCityCode() == null ? "" : config.getCityCode().trim();
        return code.isEmpty() || "0".equals(code) ? NATIONWIDE_CITY_CODE : code;
    }

    /**
     * 判定一个岗位并按结果投递。
     *
     * @param keyword String，必填，当前关键词
     * @param job     PanelJob，必填，右侧详情区读到的岗位
     * @param index   int，必填，卡片序号（从 0 开始，仅用于进度）
     */
    private void handleJob(String keyword, PanelJob job, int index) {
        // 名称规则不依赖 AI，先排除明显不相关的岗位
        MatchDecision titleCheck = jobMatchService.checkTitle(job.title());
        if (titleCheck != null) {
            record(job, titleCheck.stage(), titleCheck.reason(), null, null, "已过滤");
            zhilianService.updateMatchResult(job.jobId(), "已过滤", titleCheck.stage() + "：" + titleCheck.reason());
            return;
        }
        if (job.applyText() == null || !job.applyText().contains("立即投递")) {
            String reason = "详情区按钮为「" + (job.applyText() == null ? "无" : job.applyText()) + "」，不是立即投递";
            record(job, "投递状态", reason, null, null, "已投递跳过");
            return;
        }

        evaluatedCount++;
        sendProgress("正在判定：" + job.title(), index + 1, page.locator(CARD).count());
        boolean greet = Boolean.TRUE.equals(config.getSendGreeting());
        MatchDecision decision = greet
                ? jobMatchService.evaluate(candidate(keyword, job), config.getSayHi(), Boolean.TRUE.equals(config.getEnableAi()))
                : jobMatchService.evaluate(candidate(keyword, job), null, false);
        if (decision.aiUnavailable()) {
            consecutiveAiErrors++;
            record(job, decision.stage(), decision.reason(), null, null, "AI不可用未发送");
            zhilianService.updateMatchResult(job.jobId(), "未投递", decision.reason());
            if (consecutiveAiErrors >= MAX_CONSECUTIVE_AI_ERRORS) {
                abortReason = "AI 连续 " + consecutiveAiErrors + " 次不可用，任务已自动停止，未投递任何岗位。最后一次原因："
                        + decision.reason();
                log.error(abortReason);
                sendProgress(abortReason, null, null);
            }
            return;
        }
        consecutiveAiErrors = 0;
        if (!decision.pass()) {
            record(job, decision.stage(), decision.reason(), decision.score(), null, "已过滤");
            zhilianService.updateMatchResult(job.jobId(), "已过滤", decision.stage() + "：" + decision.reason());
            return;
        }
        log.info("智联匹配通过 | 分数：{} | 公司：{} | 岗位：{} | 原因：{}",
                decision.score(), job.company(), job.title(), decision.reason());
        Map<String, Object> evaluation = record(job, decision.stage(), decision.reason(), decision.score(),
                decision.greeting(), "处理中");

        if (dryRun) {
            evaluation.put("action", "测试通过未发送");
            zhilianService.updateMatchResult(job.jobId(), "未投递",
                    "单次测试通过（未投递，" + decision.score() + " 分）：" + decision.reason());
            deliveredCount++;
            return;
        }

        sendProgress("正在投递：" + job.title(), index + 1, page.locator(CARD).count());
        String action = deliver(job, decision.greeting());
        evaluation.put("action", action);
        if (action.startsWith("已投递")) {
            deliveredCount++;
            zhilianService.logGreeting(job.jobId(), job.title(), job.company());
            zhilianService.updateMatchResult(job.jobId(), "已投递",
                    "通过（" + decision.score() + " 分）：" + decision.reason() + "；" + action);
            Integer limit = config.getDailyGreetLimit();
            if (limit != null && limit > 0) {
                int today = zhilianService.countTodayGreetings();
                if (today >= limit) {
                    limitNotice = "今日已投递 " + today + " 次，达到每日上限 " + limit + " 次，任务已自动停止";
                    log.info(limitNotice);
                }
            }
            pauseBetweenJobs();
        } else {
            zhilianService.updateMatchResult(job.jobId(), "投递失败", action);
            humanPause(3000, 6000);
        }
    }

    /**
     * 点击「立即投递」完成平台默认投递（不进聊天、不发图片）。
     *
     * @param job      PanelJob，必填，要投递的岗位
     * @param greeting String，可空，仅保留在判定记录中，不再发送
     * @return String，处理结果：已投递 / 投递失败：原因
     */
    private String deliver(PanelJob job, String greeting) {
        Page detail = null;
        try {
            detail = openDetailTab(job);
            if (abortReason != null) return "投递失败：" + abortReason;

            // 详情页打不开或版式对不上时，回退到列表右侧详情区（那里本来就有「立即投递」）
            Page host = detail;
            if (host == null) {
                log.info("详情页未能就绪，改用列表右侧详情区投递 | 公司：{} | 岗位：{}", job.company(), job.title());
                host = page;
            } else if (checkSecurityVerify(host)) {
                return "投递失败：触发智联安全验证";
            }

            humanPause(1500, 3000);
            try { host.mouse().wheel(0, 200 + ThreadLocalRandom.current().nextInt(300)); } catch (Throwable ignore) { }
            humanPause(1000, 2500);

            Locator apply = findApplyBtn(host);
            if ((apply == null || apply.count() == 0) && host != page) {
                log.warn("详情页未找到投递按钮，回退列表页 | url={} title={}", safeUrl(host), safeTitle(host));
                closeQuietly(detail);
                detail = null;
                host = page;
                apply = findApplyBtn(host);
            }
            if (apply == null || apply.count() == 0) {
                return "投递失败：未能打开岗位详情页（页面上未找到「立即投递」按钮）";
            }
            String text = apply.first().innerText().trim();
            if (!text.contains("立即投递")) return "投递失败：详情页按钮为「" + text + "」";
            apply.first().click();
            log.info("已点击立即投递 | 公司：{} | 岗位：{} | 页面：{}", job.company(), job.title(),
                    host == page ? "列表页" : "详情页");

            Locator modal = host.locator(".deliver-greeting-modal");
            boolean delivered = waitVisible(modal, 15);
            if (!delivered) {
                if (checkPlatformLimit(host)) return "投递失败：" + limitNotice;
                // 没有弹出投递结果框时，以按钮状态再确认一次
                String after = apply.count() > 0 ? apply.first().innerText().trim() : "";
                if (after.contains("立即投递")) return "投递失败：点击后未出现投递结果";
                log.info("未出现投递结果框，按钮已变为「{}」，按已投递处理", after);
            }
            // 平台默认投递已完成；不再进聊天补发招呼语/图片
            closeModal(host);
            return "已投递";
        } catch (Throwable e) {
            log.error("智联投递过程出错 | 岗位：{} | {}", job.title(), e.getMessage(), e);
            return "投递失败：" + e.getMessage();
        } finally {
            if (detail != null) closeQuietly(detail);
            try { page.bringToFront(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 打开岗位详情页：优先按岗位链接直开新标签，失败再点「查看更多职位信息」。
     * 打开后必须等到投递按钮（或页面出现「立即投递」文案）才算成功。
     *
     * @param job PanelJob，必填，含 jobLink
     * @return Page，详情页；失败返回 null
     */
    private Page openDetailTab(PanelJob job) {
        String link = job == null ? null : job.jobLink();
        if (link != null && !link.isBlank()) {
            String url = link.startsWith("//") ? "https:" + link : link;
            Page p = null;
            try {
                p = page.context().newPage();
                p.navigate(url, new Page.NavigateOptions().setTimeout(30_000));
                if (checkSecurityVerify(p)) {
                    closeQuietly(p);
                    return null;
                }
                if (waitApplyReady(p, 15_000)) return p;
                log.warn("直开智联详情页未找到投递按钮 | url={} title={}", safeUrl(p), safeTitle(p));
                closeQuietly(p);
            } catch (Throwable e) {
                log.warn("直开智联岗位详情页失败 | {} | {}", url, e.getMessage());
                closeQuietly(p);
            }
        }

        Locator more = page.locator(VIEW_MORE);
        if (more.count() == 0) {
            log.warn("列表页未找到「查看更多职位信息」链接，无法打开详情页");
            return null;
        }
        Page p = null;
        try {
            more.first().evaluate("el => { el.target = '_blank'; }");
            p = page.waitForPopup(new Page.WaitForPopupOptions().setTimeout(10_000), () -> more.first().click());
            p.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(15_000));
            if (checkSecurityVerify(p)) {
                closeQuietly(p);
                return null;
            }
            if (waitApplyReady(p, 15_000)) return p;
            log.warn("点击链接打开的详情页未找到投递按钮 | url={} title={}", safeUrl(p), safeTitle(p));
            closeQuietly(p);
            return null;
        } catch (Throwable e) {
            log.warn("打开智联岗位详情页失败：{} | url={}", e.getMessage(), safeUrl(p));
            closeQuietly(p);
            return null;
        }
    }

    /**
     * 等待页面出现可用的「立即投递」按钮。
     *
     * @param p         Page，必填，详情页或列表页
     * @param timeoutMs long，必填，最长等待毫秒
     * @return boolean，找到可见按钮（或正文已出现文案）时为 true
     */
    private static boolean waitApplyReady(Page p, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (p.isClosed()) return false;
                Locator apply = findApplyBtn(p);
                if (apply != null && apply.count() > 0 && apply.first().isVisible()) return true;
                // 版式变化时：正文已渲染出文案，再给选择器一点时间
                Object hasText = p.evaluate("() => (document.body && document.body.innerText || '').includes('立即投递')");
                if (Boolean.TRUE.equals(hasText)) {
                    PlaywrightUtil.sleepMillis(400);
                    apply = findApplyBtn(p);
                    if (apply != null && apply.count() > 0) return true;
                }
            } catch (Throwable ignore) {
            }
            PlaywrightUtil.sleepMillis(300);
        }
        return false;
    }

    /**
     * 在页面上查找「立即投递」按钮，兼容列表右侧详情区与独立详情页的不同版式。
     *
     * @param p Page，必填，目标页面
     * @return Locator，可能为空的定位器
     */
    private static Locator findApplyBtn(Page p) {
        for (String selector : APPLY_SELECTORS) {
            try {
                Locator loc = p.locator(selector);
                if (loc.count() > 0) return loc;
            } catch (Throwable ignore) {
            }
        }
        try {
            return p.locator("button, a, [role='button']")
                    .filter(new Locator.FilterOptions().setHasText("立即投递"));
        } catch (Throwable e) {
            return p.locator(APPLY_BTN);
        }
    }

    /**
     * 安全读取页面标题。
     *
     * @param p Page，可空
     * @return String，标题；失败时为空串
     */
    private static String safeTitle(Page p) {
        try {
            return p == null ? "" : p.title();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 在投递结果框中点击「继续沟通」进入聊天，兼容新开标签页与当前标签页跳转两种情况。
     *
     * @param detail Page，必填，岗位详情页
     * @param modal  Locator，必填，投递结果框
     * @return Page，聊天页（已出现输入框）；进入失败时返回 null
     */
    private Page openChat(Page detail, Locator modal) {
        Locator btn = modal.locator("button:has-text('继续沟通')");
        if (btn.count() == 0) {
            log.warn("投递结果框中没有「继续沟通」按钮");
            return null;
        }
        humanPause(1000, 2000);
        Page chat = null;
        try {
            chat = detail.context().waitForPage(new com.microsoft.playwright.BrowserContext.WaitForPageOptions().setTimeout(8_000),
                    () -> btn.first().click());
        } catch (Throwable e) {
            // 没有新标签页时，按当前标签页跳转处理
            chat = detail;
        }
        try {
            chat.waitForSelector(CHAT_INPUT, new Page.WaitForSelectorOptions().setTimeout(20_000));
            return chat;
        } catch (Throwable e) {
            log.warn("进入智联聊天失败，页面：{}，原因：{}", safeUrl(chat), e.getMessage());
            if (chat != detail) closeQuietly(chat);
            return null;
        }
    }

    /**
     * 核对聊天会话顶部的岗位名称与要投递的岗位一致，避免把消息发到其他会话。
     *
     * @param chat     Page，必填，聊天页
     * @param jobTitle String，必填，岗位名称
     * @return boolean，一致时为 true
     */
    private boolean chatMatchesJob(Page chat, String jobTitle) {
        for (int i = 0; i < 10; i++) {
            try {
                Locator t = chat.locator(".im-chat-header__job-title");
                if (t.count() > 0) {
                    String header = normalize(t.first().innerText());
                    String want = normalize(jobTitle);
                    if (!header.isEmpty() && (header.contains(want) || want.contains(header))) return true;
                    if (i == 9) log.warn("聊天会话岗位不一致：会话「{}」，岗位「{}」", header, want);
                }
            } catch (Throwable ignore) {
            }
            PlaywrightUtil.sleep(1);
        }
        return false;
    }

    /**
     * 在聊天中发送一条文本，并确认聊天记录中出现了本人发送的这条消息。
     *
     * @param chat Page，必填，聊天页
     * @param text String，必填，要发送的文本
     * @return boolean，确认发送成功时为 true
     */
    private boolean sendText(Page chat, String text) {
        try {
            int before = countOwn(chat, ".im-msg-text");
            Locator input = chat.locator(CHAT_INPUT).first();
            input.click();
            input.fill(text);
            humanPause(800, 1600);
            Locator send = chat.locator(CHAT_SEND);
            if (send.count() == 0) {
                log.warn("未找到聊天发送按钮");
                return false;
            }
            send.first().click();
            String head = text.length() > 10 ? text.substring(0, 10) : text;
            for (int i = 0; i < 15; i++) {
                PlaywrightUtil.sleep(1);
                if (countOwn(chat, ".im-msg-text") > before) {
                    Object last = chat.evaluate("() => { const xs = document.querySelectorAll('.im-message__bubble--me .im-msg-text');"
                            + " return xs.length ? xs[xs.length - 1].innerText : ''; }");
                    if (last != null && last.toString().contains(head)) {
                        log.info("招呼语已发送：{}", text);
                        return true;
                    }
                }
            }
            log.warn("15 秒内未在聊天记录中看到发出的招呼语");
            return false;
        } catch (Throwable e) {
            log.warn("发送招呼语失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 按上传顺序逐张发送图片简历（与 Boss 共用图片），每张都等到聊天记录中出现本人图片消息并稳定后再发下一张。
     *
     * @param chat Page，必填，聊天页
     * @return int，确认发送成功的张数；某张失败即停止发送后续图片
     */
    private int sendImages(Page chat) {
        List<Path> images = bossResumeImageService.getCurrentImagePaths();
        if (images.isEmpty()) {
            log.warn("未上传图片简历，跳过图片发送；请先在 Boss 管理页面上传");
            return 0;
        }
        int sent = 0;
        for (int i = 0; i < images.size(); i++) {
            if (stopRequested()) break;
            String label = (i + 1) + "/" + images.size() + " " + images.get(i).getFileName();
            try {
                Locator input = chat.locator(CHAT_IMAGE_INPUT);
                if (input.count() == 0) {
                    log.warn("未找到聊天图片上传控件，已发送 {}/{} 张", sent, images.size());
                    break;
                }
                int before = countOwnImages(chat);
                input.first().setInputFiles(images.get(i));
                log.info("已选择图片简历 {}，等待发送完成", label);
                if (!waitImageSent(chat, before)) {
                    log.warn("图片简历 {} 未确认发送成功，停止发送后续图片", label);
                    break;
                }
                sent++;
                if (i < images.size() - 1) PlaywrightUtil.sleep(2);
            } catch (Throwable e) {
                log.warn("发送图片简历 {} 失败：{}", label, e.getMessage());
                break;
            }
        }
        log.info("智联图片简历发送 {}/{} 张", sent, images.size());
        return sent;
    }

    /**
     * 等待聊天记录中本人图片消息数增加，且 3 秒内保持不变、没有发送失败标记。
     *
     * @param chat   Page，必填，聊天页
     * @param before int，必填，选择图片前本人图片消息数
     * @return boolean，确认发送成功时为 true
     */
    private boolean waitImageSent(Page chat, int before) {
        long deadline = System.currentTimeMillis() + IMAGE_SEND_CONFIRM_TIMEOUT_SECONDS * 1000L;
        long stableSince = 0;
        while (System.currentTimeMillis() < deadline) {
            PlaywrightUtil.sleepMillis(500);
            int now = countOwnImages(chat);
            boolean failed = Boolean.TRUE.equals(chat.evaluate("() => { const xs = document.querySelectorAll('.im-message__bubble--me');"
                    + " const last = xs[xs.length - 1]; return !!last && /失败|重发|重试/.test(last.innerText); }"));
            if (failed) return false;
            if (now > before) {
                if (stableSince == 0) stableSince = System.currentTimeMillis();
                if (System.currentTimeMillis() - stableSince >= 3000) return true;
            } else {
                stableSince = 0;
            }
        }
        return false;
    }

    /**
     * 统计本人消息中匹配选择器的元素数量。
     *
     * @param chat     Page，必填，聊天页
     * @param selector String，必填，本人气泡内的子元素选择器
     * @return int，数量；读取失败时为 0
     */
    private int countOwn(Page chat, String selector) {
        try {
            Object n = chat.evaluate("sel => document.querySelectorAll('.im-message__bubble--me ' + sel).length", selector);
            return n instanceof Number num ? num.intValue() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * 统计本人发送的图片消息数（本人气泡内除头像、标记图标外的图片）。
     *
     * @param chat Page，必填，聊天页
     * @return int，数量；读取失败时为 0
     */
    private int countOwnImages(Page chat) {
        try {
            Object n = chat.evaluate("() => Array.from(document.querySelectorAll('.im-message__bubble--me'))"
                    + ".filter(b => Array.from(b.querySelectorAll('img')).some(img => !/avatar|icon|tag/i.test(img.className))).length");
            return n instanceof Number num ? num.intValue() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * 确保第 index 个卡片已加载；不够时滚动到列表底部触发加载，最多尝试 5 次。
     *
     * @param index int，必填，卡片序号（从 0 开始）
     * @return boolean，卡片存在时为 true
     */
    private boolean ensureCardLoaded(int index) {
        for (int i = 0; i < 5; i++) {
            if (page.locator(CARD).count() > index) return true;
            try {
                page.locator(CARD).last().scrollIntoViewIfNeeded();
                page.mouse().wheel(0, 1200);
            } catch (Throwable ignore) {
            }
            humanPause(3000, 6000);
        }
        return page.locator(CARD).count() > index;
    }

    /**
     * 不点开卡片，按卡片上的岗位名称、公司、地点判断是否近期已处理，避免重复点击已处理岗位。
     *
     * @param index int，必填，卡片序号（从 0 开始）
     * @return boolean，true 表示近期已处理；卡片信息读取不全时返回 false，由点开后按岗位编号再判断
     */
    private boolean isCardRecentlyProcessed(int index) {
        Locator card = page.locator(CARD).nth(index);
        String title = text(card, ".job-card__title-clamp .vue-clamp__text");
        if (title.isEmpty()) title = text(card, ".job-card__title-clamp");
        return zhilianService.isRecentlyProcessedByCard(title, text(card, ".job-card__company-name"),
                text(card, ".job-card__location span"), processedCutoff);
    }

    /**
     * 不点开卡片，按卡片上的岗位名称执行名称规则。
     *
     * @param index int，必填，卡片序号（从 0 开始）
     * @return boolean，true 表示名称规则不通过；读不到名称时返回 false，由点开后按详情判定
     */
    private boolean isRejectedByCardTitle(int index) {
        Locator card = page.locator(CARD).nth(index);
        String title = text(card, ".job-card__title-clamp .vue-clamp__text");
        if (title.isEmpty()) title = text(card, ".job-card__title-clamp");
        return !title.isEmpty() && jobMatchService.checkTitle(title) != null;
    }

    /**
     * 点开第 index 个卡片并读取右侧详情区。
     *
     * @param index int，必填，卡片序号（从 0 开始）
     * @return PanelJob，岗位信息；详情区未切换到该岗位时返回 null
     */
    private PanelJob openCard(int index) {
        Locator card = page.locator(CARD).nth(index);
        String before = attr(page.locator(VIEW_MORE), "href");
        try {
            card.scrollIntoViewIfNeeded();
            humanPause(300, 800);
            boolean active = String.valueOf(card.getAttribute("class")).contains("job-card--active");
            card.click();
            // 等右侧详情区切换到该岗位（当前已选中的卡片除外）
            for (int i = 0; i < 20; i++) {
                PlaywrightUtil.sleepMillis(300);
                String now = attr(page.locator(VIEW_MORE), "href");
                if (now != null && (active || !now.equals(before))) break;
            }
        } catch (Throwable e) {
            log.debug("点击智联卡片失败：{}", e.getMessage());
            return null;
        }
        humanPause(CARD_VIEW_PAUSE_MIN, CARD_VIEW_PAUSE_MAX);
        try {
            String title = text(card, ".job-card__title-clamp .vue-clamp__text");
            if (title.isEmpty()) title = text(card, ".job-card__title-clamp");
            String salary = text(card, ".job-card__salary");
            String company = text(card, ".job-card__company-name");
            String location = text(card, ".job-card__location span");
            String link = attr(page.locator(VIEW_MORE), "href");
            String summary = text(page.locator(".job-detail-panel"), ".job-detail-summary");
            String desc = text(page.locator(".job-detail-panel"), ".job-description");
            String applyText = text(page.locator(".job-detail-panel"), ".job-detail-summary__apply");
            // 详情区第一行是岗位名称，与卡片不一致说明还没切换过来
            String panelTitle = summary.isEmpty() ? "" : summary.split("\n")[0].trim();
            if (!panelTitle.isEmpty() && !normalize(panelTitle).contains(normalize(title))
                    && !normalize(title).contains(normalize(panelTitle))) {
                log.info("详情区岗位「{}」与卡片「{}」不一致，跳过", panelTitle, title);
                return null;
            }
            String description = (summary + "\n" + desc).trim();
            if (description.length() > 4000) description = description.substring(0, 4000);
            return new PanelJob(extractJobId(link), link, title, company, salary, location, description,
                    applyText.isEmpty() ? null : applyText);
        } catch (Throwable e) {
            log.debug("读取智联岗位详情失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 把岗位写入 zhilian_data（已存在时不重复写入）。
     *
     * @param job PanelJob，必填，岗位信息
     */
    private void saveJob(PanelJob job) {
        if (job.jobId() == null) return;
        try {
            ZhilianJobDataEntity e = new ZhilianJobDataEntity();
            e.setJobId(job.jobId());
            e.setJobTitle(job.title());
            e.setJobLink(job.jobLink());
            e.setSalary(job.salary());
            e.setLocation(job.location());
            e.setCompanyName(job.company());
            e.setDeliveryStatus("未投递");
            zhilianService.upsertJob(e);
        } catch (Exception ex) {
            log.warn("保存智联岗位失败：{}", ex.getMessage());
        }
    }

    /**
     * 组装匹配判定用的岗位信息。
     *
     * @param keyword String，必填，关键词
     * @param job     PanelJob，必填，岗位信息
     * @return JobCandidate，判定入参
     */
    private static JobCandidate candidate(String keyword, PanelJob job) {
        return new JobCandidate("zhilian", keyword, job.title(), job.company(), job.salary(), job.location(), job.description());
    }

    /**
     * 记录一个岗位的判定结果。
     *
     * @param job      PanelJob，必填，岗位信息
     * @param stage    String，必填，判定环节
     * @param reason   String，可空，原因
     * @param score    Integer，可空，AI 分数
     * @param greeting String，可空，招呼语
     * @param action   String，必填，处理结果
     * @return Map，新增的记录（后续可补充 action）
     */
    private Map<String, Object> record(PanelJob job, String stage, String reason, Integer score, String greeting, String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        m.put("jobName", job.title() == null ? "" : job.title());
        m.put("companyName", job.company() == null ? "" : job.company());
        m.put("salary", job.salary() == null ? "" : job.salary());
        m.put("stage", stage);
        m.put("reason", reason == null ? "" : reason);
        m.put("score", score);
        m.put("greeting", greeting);
        m.put("action", action);
        evaluations.add(m);
        if (evaluations.size() > 300) evaluations.remove(0);
        log.info("智联判定 | 环节：{} | 结果：{} | 公司：{} | 岗位：{} | 原因：{}", stage, action, job.company(), job.title(), reason);
        return m;
    }

    /**
     * 当前页面是否为安全验证页；是则中止任务。
     * 入参：无。
     *
     * @return boolean，触发安全验证时为 true
     */
    private boolean checkSecurityVerify() {
        return checkSecurityVerify(page);
    }

    /**
     * 指定页面是否为安全验证页；是则中止任务，等待人工在浏览器中完成验证。
     *
     * @param p Page，必填，要检查的页面
     * @return boolean，触发安全验证时为 true
     */
    private boolean checkSecurityVerify(Page p) {
        try {
            String title = p.title();
            String url = safeUrl(p);
            if ((title != null && (title.contains("Security Verification") || title.contains("安全验证")))
                    || url.contains("verify")) {
                abortReason = "触发智联安全验证，任务已停止，请在浏览器中完成验证后再启动";
                log.warn("{}，页面：{}", abortReason, url);
                sendProgress(abortReason, null, null);
                return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /**
     * 检查页面是否提示投递已达平台上限。
     *
     * @param p Page，必填，要检查的页面
     * @return boolean，达到上限时为 true，并设置 limitNotice
     */
    private boolean checkPlatformLimit(Page p) {
        try {
            Object hit = p.evaluate("() => /达到上限|投递上限|次数已用完/.test(document.body.innerText)");
            if (Boolean.TRUE.equals(hit)) {
                limitNotice = "智联提示今日投递已达上限，任务已自动停止";
                return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /**
     * 关闭投递结果框（点「留在此页」或关闭按钮）。
     *
     * @param p Page，必填，详情页
     */
    private void closeModal(Page p) {
        try {
            Locator stay = p.locator(".deliver-greeting-modal button:has-text('留在此页')");
            if (stay.count() > 0) stay.first().click();
        } catch (Throwable ignore) {
        }
    }

    /**
     * 等待元素可见。
     *
     * @param loc     Locator，必填，元素
     * @param seconds int，必填，最长等待秒数
     * @return boolean，在时限内可见时为 true
     */
    private static boolean waitVisible(Locator loc, int seconds) {
        for (int i = 0; i < seconds * 2; i++) {
            try {
                if (loc.count() > 0 && loc.first().isVisible()) return true;
            } catch (Throwable ignore) {
            }
            PlaywrightUtil.sleepMillis(500);
        }
        return false;
    }

    /**
     * 是否应停止：用户点了停止、任务中止、达到上限或达到本次测试的数量。
     * 入参：无。
     *
     * @return boolean，true 表示应停止后续岗位与关键词
     */
    private boolean stopRequested() {
        if (abortReason != null || limitNotice != null) return true;
        if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) return true;
        if (maxDeliveries > 0 && deliveredCount >= maxDeliveries) return true;
        return maxEvaluations > 0 && evaluatedCount >= maxEvaluations;
    }

    /**
     * 两次投递之间的停顿：以 WAIT_TIME_SECONDS 为基准在 0.5~1.5 倍之间偏短随机，约 8% 概率额外停 15~45 秒。
     * 入参：无。出参：无。
     */
    private void pauseBetweenJobs() {
        long base = WAIT_TIME_SECONDS * 1000L;
        humanPause(base / 2, base * 3 / 2);
        if (ThreadLocalRandom.current().nextDouble() < 0.08) humanPause(15_000, 45_000);
    }

    /**
     * 可被停止指令打断的休息。
     *
     * @param minMillis long，必填，最短毫秒
     * @param maxMillis long，必填，最长毫秒
     */
    private void restWithStopCheck(long minMillis, long maxMillis) {
        long end = System.currentTimeMillis() + minMillis
                + (long) (Math.max(0, maxMillis - minMillis) * ThreadLocalRandom.current().nextDouble());
        while (System.currentTimeMillis() < end && !stopRequested()) {
            PlaywrightUtil.sleepMillis(1000);
        }
    }

    /**
     * 随机停顿，偏向区间较短的一端。
     *
     * @param minMillis long，必填，最短毫秒
     * @param maxMillis long，必填，最长毫秒
     */
    private static void humanPause(long minMillis, long maxMillis) {
        long span = Math.max(0, maxMillis - minMillis);
        double r = Math.pow(ThreadLocalRandom.current().nextDouble(), 1.6);
        PlaywrightUtil.sleepMillis(minMillis + (long) (span * r));
    }

    /**
     * 从岗位链接中取岗位编号（jobdetail/{编号}.htm）。
     *
     * @param link String，可空，岗位链接
     * @return String，岗位编号；取不到时为 null
     */
    private static String extractJobId(String link) {
        if (link == null) return null;
        int i = link.indexOf("jobdetail/");
        int j = link.indexOf(".htm", i);
        if (i < 0 || j <= i) return null;
        String id = link.substring(i + "jobdetail/".length(), j);
        return id.isBlank() ? null : id;
    }

    /**
     * 去掉空白并转小写，用于岗位名称比较。
     *
     * @param s String，可空
     * @return String，规范化后的文本，不为 null
     */
    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 安全读取子元素文本。
     *
     * @param root     Locator，必填，父元素
     * @param selector String，必填，子元素选择器
     * @return String，去首尾空白的文本；不存在时为空串
     */
    private static String text(Locator root, String selector) {
        try {
            Locator el = root.locator(selector);
            return el.count() > 0 ? el.first().innerText().trim() : "";
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 安全读取第一个元素的属性。
     *
     * @param loc  Locator，必填，元素
     * @param name String，必填，属性名
     * @return String，属性值；不存在时为 null
     */
    private static String attr(Locator loc, String name) {
        try {
            return loc.count() > 0 ? loc.first().getAttribute(name) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 安全读取页面地址。
     *
     * @param p Page，可空
     * @return String，地址；失败时为空串
     */
    private static String safeUrl(Page p) {
        try {
            return p == null ? "" : p.url();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 关闭标签页（列表主页面除外），忽略异常。
     *
     * @param p Page，可空
     */
    private void closeQuietly(Page p) {
        if (p == null || p == page) return;
        try { p.close(); } catch (Throwable ignore) { }
    }

    /**
     * 输出进度。
     *
     * @param message String，必填，进度文本
     * @param current Integer，可空，当前序号
     * @param total   Integer，可空，总数
     */
    private void sendProgress(String message, Integer current, Integer total) {
        if (progressCallback != null) progressCallback.accept(message, current, total);
    }

    /**
     * 本次任务的逐岗位判定记录（只读副本）。
     * 入参：无。
     *
     * @return List&lt;Map&gt;，每项含 time、jobName、companyName、salary、stage、reason、score、greeting、action
     */
    public List<Map<String, Object>> getEvaluations() {
        return new ArrayList<>(evaluations);
    }

    /**
     * 本次任务已投递的岗位数（只评估模式下为找到的可投递岗位数）。
     * 入参：无。
     *
     * @return int，数量
     */
    public int getDeliveredCount() {
        return deliveredCount;
    }

    /**
     * 中止原因。
     * 入参：无。
     *
     * @return String，中止原因；未中止时为 null
     */
    public String getAbortReason() {
        return abortReason;
    }

    /**
     * 达到上限时的提示。
     * 入参：无。
     *
     * @return String，提示文本；未达到时为 null
     */
    public String getLimitNotice() {
        return limitNotice;
    }

    /**
     * 本次任务遇到的新岗位数。
     * 入参：无。
     *
     * @return int，数量
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
     * 入参：无。
     *
     * @return int，数量
     */
    public int getSkippedOldCount() {
        return skippedOldCount;
    }
}
