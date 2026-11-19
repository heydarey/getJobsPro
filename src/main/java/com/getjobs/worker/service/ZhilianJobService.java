package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.JobMatchService;
import com.getjobs.application.service.MatchRuleService;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.zhilian.ZhiLian;
import com.getjobs.worker.zhilian.ZhilianConfig;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 智联招聘任务服务：管理投递任务的执行、停止与状态。
 * <p>
 * 与 Boss 相同，每次任务分配一个运行编号（runId）。停止时作废当前编号，旧任务在任何检查点都会停止，不会再投递。
 * 招呼语的固定文本与 AI 开关与 Boss 共用（取自 Boss 配置），图片简历使用 Boss 页上传的图片。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZhilianJobService implements JobPlatformService {
    private static final String PLATFORM = "zhilian";

    /** 单次测试最多评估的岗位数 */
    private static final int TEST_MAX_EVALUATIONS = 40;
    /** 停止指令发出后，等待任务自行退出的宽限时间 */
    private static final long FORCE_RESET_GRACE_MS = 15_000L;
    /** 近期已处理岗位的回看天数 */
    private static final int RECENT_DAYS = 3;

    private final PlaywrightManager playwrightManager;
    private final ObjectProvider<ZhiLian> zhilianProvider;
    private final ConfigService configService;
    private final JobMatchService jobMatchService;
    private final MatchRuleService matchRuleService;
    private final ZhilianService zhilianService;

    /** 运行编号生成器 */
    private final AtomicLong runSeq = new AtomicLong();
    /** 当前有效的运行编号；0 表示没有任务 */
    private volatile long activeRunId = 0L;
    /** 任务运行状态 */
    private volatile boolean isRunning = false;
    /** 已发出停止指令、等待任务退出 */
    private volatile boolean stopping = false;
    /** 运行模式：normal 正式投递 / test 单次测试（真实投递 1 个）/ testDry 单次测试（只评估） */
    private volatile String runMode = "";
    /** 最近一条进度消息 */
    private volatile String lastMessage = "";
    /** 当前（或最近一次）任务实例，用于读取判定记录 */
    private volatile ZhiLian current;
    /** 最近一次任务的结束结果 */
    private volatile Map<String, Object> lastResult = new HashMap<>();

    /**
     * 正式投递入口（兼容 JobPlatformService 接口）。
     *
     * @param progressCallback Consumer&lt;JobProgressMessage&gt;，必填，进度回调
     */
    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        long runId = tryAcquire("normal");
        if (runId == 0L) {
            progressCallback.accept(JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
            return;
        }
        runDelivery(runId, "normal", progressCallback);
    }

    /**
     * 抢占运行状态。成功后调用方必须用返回的运行编号调用 {@link #runDelivery(long, String, Consumer)}。
     *
     * @param mode String，必填，normal / test / testDry
     * @return long，运行编号；已有任务在跑时返回 0
     */
    public synchronized long tryAcquire(String mode) {
        if (isRunning) return 0L;
        long runId = runSeq.incrementAndGet();
        activeRunId = runId;
        isRunning = true;
        stopping = false;
        runMode = mode;
        lastMessage = "任务启动中";
        current = null;
        lastResult = new HashMap<>();
        return runId;
    }

    /**
     * 执行一次已抢占运行状态的任务（同步执行，调用方自行放到异步线程）。
     *
     * @param runId            long，必填，{@link #tryAcquire(String)} 返回的运行编号
     * @param mode             String，必填，normal / test / testDry
     * @param progressCallback Consumer&lt;JobProgressMessage&gt;，必填，进度回调
     */
    public void runDelivery(long runId, String mode, Consumer<JobProgressMessage> progressCallback) {
        Consumer<JobProgressMessage> cb = pm -> {
            if (activeRunId == runId) lastMessage = pm.getMessage();
            progressCallback.accept(pm);
        };
        boolean success = false;
        try {
            cb.accept(JobProgressMessage.info(PLATFORM, "检查浏览器状态..."));
            playwrightManager.ensureReady();
            if (playwrightManager.ensurePlatformPage("zhilian")) {
                cb.accept(JobProgressMessage.info(PLATFORM, "智联招聘标签页已被关闭，已重新打开"));
            }
            Page page = playwrightManager.getZhilianPage();
            if (page == null) throw new IllegalStateException("智联招聘页面未初始化");
            if (!playwrightManager.isLoggedIn(PLATFORM)) throw new IllegalStateException("请先登录智联招聘");

            ZhilianConfig config = configService.getZhilianConfig();
            BossConfig boss = configService.getBossConfig();
            config.setSayHi(boss.getSayHi());
            config.setEnableAi(Boolean.TRUE.equals(boss.getEnableAI()));
            // 智联只做平台默认「立即投递」（自带在线简历+账号默认招呼语）。
            // 不再进聊天补发；图片简历含二维码会被智联官方拦截。
            config.setSendGreeting(false);
            config.setSendImgResume(false);
            if (config.getKeywords() == null || config.getKeywords().isEmpty()) {
                throw new IllegalStateException("智联关键词为空，请先配置或点「从Boss同步」");
            }

            cb.accept(JobProgressMessage.info(PLATFORM, "投递前自检（匹配规则、AI）..."));
            List<String> problems = jobMatchService.preflight(null);
            if (!problems.isEmpty()) {
                throw new IllegalStateException("自检未通过，任务未启动：" + String.join("；", problems));
            }
            Integer limit = config.getDailyGreetLimit();
            if (!"testDry".equals(mode) && limit != null && limit > 0) {
                int today = zhilianService.countTodayGreetings();
                if (today >= limit) {
                    throw new IllegalStateException("今日已投递 " + today + " 次，已达到每日上限 " + limit + " 次，任务未启动");
                }
            }

            playwrightManager.pauseZhilianMonitoring();

            ZhiLian zhilian = zhilianProvider.getObject();
            zhilian.setPage(page);
            zhilian.setConfig(config);
            zhilian.setProgressCallback((message, cur, total) -> {
                if (cur != null && total != null) {
                    cb.accept(JobProgressMessage.progress(PLATFORM, message, cur, total));
                } else {
                    cb.accept(JobProgressMessage.info(PLATFORM, message));
                }
            });
            zhilian.setShouldStopCallback(() -> activeRunId != runId || stopping);
            if (!"normal".equals(mode)) {
                zhilian.setMaxDeliveries(1);
                zhilian.setMaxEvaluations(TEST_MAX_EVALUATIONS);
                zhilian.setDryRun("testDry".equals(mode));
            }
            LocalDateTime cutoff = LocalDateTime.now().minusDays(RECENT_DAYS);
            LocalDateTime ruleUpdated = matchRuleService.getUpdatedAt();
            if (ruleUpdated != null && ruleUpdated.isAfter(cutoff)) cutoff = ruleUpdated;
            zhilian.setProcessedCutoff(cutoff);
            current = zhilian;
            zhilian.prepare();

            cb.accept(JobProgressMessage.info(PLATFORM, "test".equals(mode) ? "单次测试开始（找到 1 个匹配岗位即真实投递并结束）"
                    : "testDry".equals(mode) ? "单次测试开始（只评估，不投递）" : "开始投递任务..."));
            playwrightManager.callOnPlaywright(zhilian::execute);
            if (zhilian.getAbortReason() != null) throw new IllegalStateException(zhilian.getAbortReason());

            int delivered = zhilian.getDeliveredCount();
            String jobs = String.format("（新岗位 %d 个，跳过近期已处理 %d 个，名称预过滤 %d 次）", zhilian.getNewJobCount(), zhilian.getSkippedOldCount(), zhilian.getTitleFilteredCount());
            String summary;
            if (stopping) {
                summary = "已按停止指令结束" + jobs;
            } else if (zhilian.getLimitNotice() != null) {
                summary = zhilian.getLimitNotice() + jobs;
            } else if (zhilian.getNewJobCount() == 0) {
                summary = "近期没有新岗位，任务已自动停止" + jobs;
            } else if ("testDry".equals(mode)) {
                summary = (delivered > 0 ? "单次测试完成：找到 1 个可投递岗位（未投递），详见判定记录"
                        : "单次测试完成：评估范围内没有可投递岗位，详见判定记录") + jobs;
            } else if ("test".equals(mode)) {
                summary = (delivered > 0 ? "单次测试完成：已向 1 个匹配岗位投递，详见判定记录"
                        : "单次测试完成：评估范围内没有可投递岗位，未投递") + jobs;
            } else {
                summary = String.format("投递任务完成，共投递%d个岗位", delivered) + jobs;
            }
            success = true;
            cb.accept(JobProgressMessage.success(PLATFORM, summary));
        } catch (Exception e) {
            log.error("智联招聘投递任务执行失败", e);
            cb.accept(JobProgressMessage.error(PLATFORM, e.getMessage()));
        } finally {
            Map<String, Object> result = new HashMap<>();
            result.put("mode", mode);
            result.put("success", success);
            result.put("message", lastMessage);
            result.put("finishedAt", LocalDateTime.now().withNano(0).toString());
            synchronized (this) {
                if (activeRunId == runId) {
                    lastResult = result;
                    isRunning = false;
                    stopping = false;
                    activeRunId = 0L;
                    try { playwrightManager.resumeZhilianMonitoring(); } catch (Exception ignored) { }
                }
            }
        }
    }

    /**
     * 发出停止指令。任务在检查点自行退出；超过宽限时间仍未退出时作废运行编号并复位状态。
     * 入参：无。出参：无。
     */
    @Override
    public synchronized void stopDelivery() {
        if (!isRunning) return;
        log.info("收到停止智联招聘投递任务的请求");
        stopping = true;
        lastMessage = "正在停止...";
        final long runId = activeRunId;
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + FORCE_RESET_GRACE_MS;
            while (activeRunId == runId && isRunning && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            synchronized (ZhilianJobService.this) {
                if (activeRunId == runId && isRunning) {
                    log.warn("智联投递任务在 {} 秒内未退出，作废运行编号并复位状态", FORCE_RESET_GRACE_MS / 1000);
                    activeRunId = 0L;
                    isRunning = false;
                    stopping = false;
                    Map<String, Object> result = new HashMap<>();
                    result.put("mode", runMode);
                    result.put("success", false);
                    result.put("message", "已强制停止");
                    result.put("finishedAt", LocalDateTime.now().withNano(0).toString());
                    lastResult = result;
                    try { playwrightManager.resumeZhilianMonitoring(); } catch (Exception ignored) { }
                }
            }
        }, "zhilian-stop-watchdog").start();
    }

    /**
     * 当前任务状态，供管理页面轮询。
     * 入参：无。
     *
     * @return Map，platform、isRunning、stopping、runMode、lastMessage、isLoggedIn、
     * evaluations（最近 100 条判定记录）、lastResult、todayGreetings
     */
    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("stopping", stopping);
        status.put("runMode", runMode);
        status.put("lastMessage", lastMessage);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        ZhiLian z = current;
        List<Map<String, Object>> evals = z == null ? new ArrayList<>() : z.getEvaluations();
        if (evals.size() > 100) evals = evals.subList(evals.size() - 100, evals.size());
        status.put("evaluations", evals);
        status.put("lastResult", lastResult);
        status.put("todayGreetings", zhilianService.countTodayGreetings());
        return status;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 是否已发出停止指令（兼容旧调用）。
     * 入参：无。
     *
     * @return boolean，已发出停止指令时为 true
     */
    public boolean shouldStop() {
        return stopping;
    }
}
