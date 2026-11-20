package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.JobMatchService;
import com.getjobs.application.service.MatchRuleService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.BossRiskAlertService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
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
 * Boss直聘任务服务：管理投递任务的执行、停止与状态。
 * <p>
 * 每次任务分配一个运行编号（runId）。停止时作废当前编号，旧任务的停止判断以编号为准，
 * 即使状态被强制复位、又启动了新任务，旧任务也不会再发送任何消息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BossJobService implements JobPlatformService {
    private static final String PLATFORM = "boss";

    /** 单次测试最多评估的岗位数，避免测试跑太久 */
    private static final int TEST_MAX_EVALUATIONS = 40;
    /** 停止指令发出后，等待任务自行退出的宽限时间 */
    private static final long FORCE_RESET_GRACE_MS = 15_000L;

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Boss> bossProvider;
    private final JobMatchService jobMatchService;
    private final MatchRuleService matchRuleService;
    private final BossService bossService;
    private final BossRiskAlertService bossRiskAlertService;

    /** 近期已处理岗位的回看天数：这段时间内判定或发送过的岗位直接跳过 */
    private static final int RECENT_DAYS = 3;

    /** 运行编号生成器 */
    private final AtomicLong runSeq = new AtomicLong();
    /** 当前有效的运行编号；0 表示没有任务 */
    private volatile long activeRunId = 0L;
    /** 任务运行状态（从点击开始到任务线程真正退出） */
    private volatile boolean isRunning = false;
    /** 已发出停止指令、等待任务退出 */
    private volatile boolean stopping = false;
    /** 本次任务的开始时间（毫秒） */
    private volatile long runningSince = 0L;
    /** 运行模式：normal 正式投递 / test 单次测试（真实发送 1 个）/ testDry 单次测试（只评估） */
    private volatile String runMode = "";
    /** 最近一条进度消息 */
    private volatile String lastMessage = "";
    /** 当前（或最近一次）任务的 Boss 实例，用于读取逐岗位判定记录 */
    private volatile Boss currentBoss;
    /** 最近一次任务的结束结果 */
    private volatile Map<String, Object> lastResult = new HashMap<>();

    /**
     * 正式投递入口（兼容 JobPlatformService 接口）。
     *
     * @param progressCallback Consumer&lt;JobProgressMessage&gt;，必填，进度回调
     */
    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        runDelivery("normal", progressCallback);
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
        runningSince = System.currentTimeMillis();
        lastMessage = "任务启动中";
        currentBoss = null;
        lastResult = new HashMap<>();
        return runId;
    }

    /**
     * 抢占运行状态并执行（同步执行，调用方自行放到异步线程）。
     *
     * @param mode             String，必填，normal / test / testDry
     * @param progressCallback Consumer&lt;JobProgressMessage&gt;，必填，进度回调
     */
    public void runDelivery(String mode, Consumer<JobProgressMessage> progressCallback) {
        long runId = tryAcquire(mode);
        if (runId == 0L) {
            progressCallback.accept(JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
            return;
        }
        runDelivery(runId, mode, progressCallback);
    }

    /**
     * 执行一次已抢占运行状态的任务。
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
        String summary;
        try {
            String blocked = riskAlertBlockMessage();
            if (blocked != null) {
                throw new IllegalStateException(blocked);
            }
            cb.accept(JobProgressMessage.info(PLATFORM, "检查浏览器状态..."));
            playwrightManager.ensureReady();
            if (playwrightManager.ensurePlatformPage("boss")) {
                cb.accept(JobProgressMessage.info(PLATFORM, "Boss直聘标签页已被关闭，已重新打开"));
            }

            Page page = playwrightManager.getBossPage();
            if (page == null) {
                throw new IllegalStateException("Boss页面未初始化");
            }
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                throw new IllegalStateException("请先登录Boss直聘");
            }

            BossConfig config = configService.getBossConfig();
            cb.accept(JobProgressMessage.info(PLATFORM, "投递前自检（匹配规则、AI、招呼语）..."));
            List<String> problems = jobMatchService.preflight(config.getSayHi());
            if (!problems.isEmpty()) {
                throw new IllegalStateException("自检未通过，任务未启动：" + String.join("；", problems));
            }
            Integer limit = config.getDailyGreetLimit();
            if (!"testDry".equals(mode) && limit != null && limit > 0) {
                int today = bossService.countTodayGreetings();
                if (today >= limit) {
                    throw new IllegalStateException("今日已打招呼 " + today + " 次，已达到每日上限 " + limit + " 次，任务未启动");
                }
            }

            playwrightManager.pauseBossMonitoring();

            Boss boss = bossProvider.getObject();
            boss.setPage(page);
            boss.setConfig(config);
            boss.setProgressCallback((message, current, total) -> {
                if (current != null && total != null) {
                    cb.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    cb.accept(JobProgressMessage.info(PLATFORM, message));
                }
            });
            // 以运行编号判断停止：编号被作废（用户停止 / 强制复位）后，旧任务永远看到“应停止”
            boss.setShouldStopCallback(() -> activeRunId != runId || stopping);
            if (!"normal".equals(mode)) {
                boss.setMaxDeliveries(1);
                boss.setMaxEvaluations(TEST_MAX_EVALUATIONS);
                boss.setDryRun("testDry".equals(mode));
            }
            // 近期已处理的判断起点：取“近 RECENT_DAYS 天”与“匹配规则最近修改时间”中较晚者，
            // 规则改过之后，之前被过滤的岗位会按新规则重新判定
            java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusDays(RECENT_DAYS);
            java.time.LocalDateTime ruleUpdated = matchRuleService.getUpdatedAt();
            if (ruleUpdated != null && ruleUpdated.isAfter(cutoff)) cutoff = ruleUpdated;
            boss.setProcessedCutoff(cutoff);
            currentBoss = boss;
            boss.prepare();

            cb.accept(JobProgressMessage.info(PLATFORM, "test".equals(mode) ? "单次测试开始（找到 1 个匹配岗位即真实发送并结束）"
                    : "testDry".equals(mode) ? "单次测试开始（只评估，不发送）" : "开始投递任务..."));
            playwrightManager.callOnPlaywright(boss::execute);
            int delivered = boss.getDeliveredCount();
            if (boss.getAbortReason() != null) {
                throw new IllegalStateException(boss.getAbortReason());
            }
            success = true;
            String newJobs = String.format("（新岗位 %d 个，跳过近期已处理 %d 个，卡片预过滤 %d 次）", boss.getNewJobCount(), boss.getSkippedOldCount(), boss.getTitleFilteredCount());
            if (stopping) {
                summary = "已按停止指令结束" + newJobs;
            } else if (boss.getLimitNotice() != null) {
                summary = boss.getLimitNotice() + newJobs;
            } else if (boss.getNewJobCount() == 0) {
                summary = "近期没有新岗位，任务已自动停止" + newJobs;
            } else if ("testDry".equals(mode)) {
                summary = delivered > 0 ? "单次测试完成：找到 1 个可发送岗位（未发送），详见判定记录"
                        : "单次测试完成：评估范围内没有可发送岗位，详见判定记录";
            } else if ("test".equals(mode)) {
                summary = delivered > 0 ? "单次测试完成：已向 1 个匹配岗位发送，详见判定记录"
                        : "单次测试完成：评估范围内没有可发送岗位，未发送任何消息";
            } else {
                summary = String.format("投递任务完成，共发起%d个聊天", delivered);
            }
            if (!stopping && boss.getLimitNotice() == null && boss.getNewJobCount() > 0) {
                summary = summary + newJobs;
            }
            cb.accept(JobProgressMessage.success(PLATFORM, summary));
        } catch (Exception e) {
            log.error("Boss投递任务执行失败", e);
            summary = e.getMessage();
            cb.accept(JobProgressMessage.error(PLATFORM, summary));
        } finally {
            Map<String, Object> result = new HashMap<>();
            result.put("mode", mode);
            result.put("success", success);
            result.put("message", lastMessage);
            result.put("finishedAt", LocalDateTime.now().withNano(0).toString());
            synchronized (this) {
                // 只有仍是当前任务时才复位，避免旧任务退出时把新任务的状态清掉
                if (activeRunId == runId) {
                    lastResult = result;
                    isRunning = false;
                    stopping = false;
                    activeRunId = 0L;
                    runningSince = 0L;
                    try { playwrightManager.resumeBossMonitoring(); } catch (Exception ignored) {}
                }
            }
        }
    }

    /**
     * 发出停止指令。任务在检查点自行退出；超过宽限时间仍未退出时作废运行编号并复位状态，
     * 旧任务此后在任何检查点都会停止，不会再发送消息。
     */
    @Override
    public synchronized void stopDelivery() {
        if (!isRunning) {
            return;
        }
        log.info("收到停止Boss投递任务的请求");
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
            synchronized (BossJobService.this) {
                if (activeRunId == runId && isRunning) {
                    log.warn("Boss投递任务在 {} 秒内未退出，作废运行编号并复位状态；旧任务不会再发送消息",
                            FORCE_RESET_GRACE_MS / 1000);
                    activeRunId = 0L;
                    isRunning = false;
                    stopping = false;
                    runningSince = 0L;
                    Map<String, Object> result = new HashMap<>();
                    result.put("mode", runMode);
                    result.put("success", false);
                    result.put("message", "已强制停止");
                    result.put("finishedAt", LocalDateTime.now().withNano(0).toString());
                    lastResult = result;
                    try { playwrightManager.resumeBossMonitoring(); } catch (Exception ignored) {}
                }
            }
        }, "boss-stop-watchdog").start();
    }

    /**
     * 任务已经运行的时长。
     *
     * @return long，毫秒；没有任务时为 0
     */
    public long runningForMillis() {
        long since = runningSince;
        return since == 0L ? 0L : System.currentTimeMillis() - since;
    }

    /**
     * 当前任务状态，供管理页面轮询同步按钮。
     *
     * @return Map，platform:String、isRunning:boolean、stopping:boolean、runMode:String、
     * runningSeconds:long、lastMessage:String、isLoggedIn:boolean、evaluations:List（最近 100 条判定记录）、
     * lastResult:Map（最近一次任务结束结果）
     */
    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("stopping", stopping);
        status.put("runMode", runMode);
        status.put("runningSeconds", runningForMillis() / 1000);
        status.put("lastMessage", lastMessage);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        Boss boss = currentBoss;
        List<Map<String, Object>> evals = boss == null ? new ArrayList<>() : boss.getEvaluations();
        if (evals.size() > 100) evals = evals.subList(evals.size() - 100, evals.size());
        status.put("evaluations", evals);
        status.put("lastResult", lastResult);
        status.put("todayGreetings", bossService.countTodayGreetings());
        status.put("riskAlert", bossRiskAlertService.getActiveAlert());
        return status;
    }

    /**
     * 存在未处理的风控提醒时，返回拒绝启动的提示。
     * 入参：无。
     *
     * @return String，拒绝原因；没有未处理提醒时为 null
     */
    public String riskAlertBlockMessage() {
        java.util.Map<String, Object> alert = bossRiskAlertService.getActiveAlert();
        if (alert == null) return null;
        return "Boss 曾触发" + alert.get("alertType") + "（" + alert.getOrDefault("createdAt", "") + "），"
                + "请先在 noVNC 中人工处理，再到 Boss 配置页点「我已处理」后启动";
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
     * 是否应该停止（兼容旧调用）。
     *
     * @return boolean，已发出停止指令时为 true
     */
    public boolean shouldStop() {
        return stopping;
    }
}
