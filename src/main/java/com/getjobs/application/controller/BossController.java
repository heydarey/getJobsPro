package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.BossRiskAlertService;
import com.getjobs.application.service.CookieService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Boss 平台控制器（单平台合并版）：进度 SSE 与任务接口
 */
@Slf4j
@RestController
@RequestMapping("/api/boss")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class BossController {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final BossJobService bossJobService;
    private final PlaywrightManager playwrightManager;
    private final CookieService cookieService;
    private final BossRiskAlertService bossRiskAlertService;

    private final List<SseEmitter> bossProgressEmitters = new CopyOnWriteArrayList<>();

    /** SSE - Boss投递任务进度推送 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBossProgress() {
        SseEmitter emitter = new SseEmitter(0L); // 永不超时
        bossProgressEmitters.add(emitter);

        emitter.onCompletion(() -> {
            log.info("Boss进度SSE连接已完成");
            bossProgressEmitters.remove(emitter);
        });
        emitter.onTimeout(() -> {
            log.info("Boss进度SSE连接超时");
            bossProgressEmitters.remove(emitter);
        });
        emitter.onError(e -> {
            log.error("Boss进度SSE连接错误", e);
            bossProgressEmitters.remove(emitter);
        });

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("message", "已连接到Boss投递进度推送")));
        } catch (IOException e) {
            log.error("发送SSE连接消息失败", e);
        }
        return emitter;
    }

    /** POST - 启动Boss投递任务 */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> executeBoss() {
        String blocked = bossJobService.riskAlertBlockMessage();
        if (blocked != null) {
            return ResponseEntity.badRequest().body(Map.of("status", "risk_alert", "message", blocked));
        }
        long runId = bossJobService.tryAcquire("normal");
        if (runId == 0L) {
            return ResponseEntity.ok(Map.of(
                    "status", "already_running",
                    "message", "Boss投递任务已在运行中"
            ));
        }

        CompletableFuture.runAsync(() -> bossJobService.runDelivery(runId, "normal", this::sendBossProgress));

        return ResponseEntity.ok(Map.of(
                "status", "started",
                "message", "Boss投递任务已启动"
        ));
    }

    /**
     * 启动正式投递（前端使用的接口）。
     * 入参：无。
     *
     * @return Map，success:boolean、message:String、status:String（started / running / not_logged_in）
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startBoss() {
        return startRun("normal");
    }

    /**
     * 单次测试：按当前配置执行一次投递流程，找到 1 个通过匹配判定的岗位即结束，最多评估 40 个岗位。
     *
     * @param dryRun Boolean，可空，默认 false；true 时只评估不发送，false 时对找到的岗位真实发送
     * @return Map，success:boolean、message:String、status:String
     */
    @PostMapping("/testDelivery")
    public ResponseEntity<Map<String, Object>> testDelivery(@RequestParam(required = false, defaultValue = "false") Boolean dryRun) {
        return startRun(Boolean.TRUE.equals(dryRun) ? "testDry" : "test");
    }

    /**
     * 抢占运行状态并异步启动任务。
     *
     * @param mode String，必填，normal / test / testDry
     * @return ResponseEntity，见 startBoss 说明
     */
    private ResponseEntity<Map<String, Object>> startRun(String mode) {
        Map<String, Object> response = new HashMap<>();
        try {
            String blocked = bossJobService.riskAlertBlockMessage();
            if (blocked != null) {
                response.put("success", false);
                response.put("message", blocked);
                response.put("status", "risk_alert");
                return ResponseEntity.badRequest().body(response);
            }
            if (!playwrightManager.isLoggedIn("boss")) {
                response.put("success", false);
                response.put("message", "请先登录Boss直聘");
                response.put("status", "not_logged_in");
                return ResponseEntity.badRequest().body(response);
            }
            long runId = bossJobService.tryAcquire(mode);
            if (runId == 0L) {
                long seconds = bossJobService.runningForMillis() / 1000;
                response.put("success", false);
                response.put("message", String.format(
                        "Boss任务已在运行中（已运行 %d 分 %d 秒），可点\"停止投递\"后再重新开始",
                        seconds / 60, seconds % 60));
                response.put("status", "running");
                response.put("runningSeconds", seconds);
                return ResponseEntity.badRequest().body(response);
            }
            CompletableFuture.runAsync(() -> bossJobService.runDelivery(runId, mode, pm -> {
                sendBossProgress(pm);
                log.info("[{}] {}", pm.getPlatform(), pm.getMessage());
            }));
            response.put("success", true);
            response.put("message", "normal".equals(mode) ? "Boss任务启动成功" : "单次测试已启动");
            response.put("status", "started");
            log.info("通过API启动Boss任务成功，模式：{}", mode);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("启动Boss任务失败", e);
            response.put("success", false);
            response.put("message", "启动Boss任务失败: " + e.getMessage());
            response.put("error", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 查询未处理的 Boss 风控提醒。
     * 入参：无。
     *
     * @return Map，active:boolean、alert:Map（见 BossRiskAlertService.getActiveAlert，无提醒时为 null）
     */
    @GetMapping("/riskAlert")
    public ResponseEntity<Map<String, Object>> riskAlert() {
        Map<String, Object> alert = bossRiskAlertService.getActiveAlert();
        Map<String, Object> response = new HashMap<>();
        response.put("active", alert != null);
        response.put("alert", alert);
        return ResponseEntity.ok(response);
    }

    /**
     * 管理员确认已在 noVNC 中处理完风控（完成验证或已解封），解除启动限制。
     * 入参：无。
     *
     * @return Map，success:boolean、message:String、count:int（本次确认的提醒条数）
     */
    @PostMapping("/riskAlert/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeRiskAlert() {
        Map<String, Object> response = new HashMap<>();
        try {
            int n = bossRiskAlertService.acknowledge();
            response.put("success", true);
            response.put("count", n);
            response.put("message", n > 0 ? "已确认处理，可以重新启动投递" : "没有未处理的风控提醒");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** POST - 停止Boss投递任务 */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopBoss() {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!bossJobService.isRunning()) {
                response.put("success", false);
                response.put("message", "没有正在运行的Boss任务");
                return ResponseEntity.badRequest().body(response);
            }
            bossJobService.stopDelivery();
            response.put("success", true);
            response.put("message", "Boss任务停止请求已发送");
            log.info("通过API停止Boss任务");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("停止Boss任务失败", e);
            response.put("success", false);
            response.put("message", "停止Boss任务失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** POST - 退出Boss登录 */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logoutBoss() {
        Map<String, Object> response = new HashMap<>();
        try {
            playwrightManager.setLoginStatus("boss", false);
            cookieService.clearCookieByPlatform("boss", "manual logout");
            try { 
                playwrightManager.clearBossCookies(); 
            } catch (Exception e) { 
                log.warn("清理Boss上下文Cookie异常: {}", e.getMessage()); 
            }
            response.put("success", true);
            response.put("message", "Boss已退出登录，数据库Cookie和上下文Cookie均已清理");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("退出登录失败", e);
            response.put("success", false);
            response.put("message", "退出登录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** GET - 获取Boss任务状态 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getBossStatus() {
        return ResponseEntity.ok(bossJobService.getStatus());
    }

    private void sendBossProgress(JobProgressMessage message) {
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : bossProgressEmitters) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(objectMapper.writeValueAsString(message)));
            } catch (Exception e) {
                if (e instanceof AsyncRequestNotUsableException ||
                        e instanceof ClientAbortException ||
                        (e.getCause() instanceof ClientAbortException) ||
                        (e instanceof IOException && String.valueOf(e.getMessage()).contains("中止了一个已建立的连接"))) {
                    log.debug("Boss进度 SSE 客户端已断开，移除连接: {}", e.getMessage());
                    try { emitter.complete(); } catch (Exception ignored) {}
                } else {
                    log.error("发送Boss进度消息失败", e);
                }
                deadEmitters.add(emitter);
            }
        }
        bossProgressEmitters.removeAll(deadEmitters);
    }

    /** 心跳 - Boss进度 SSE */
    @Scheduled(fixedRate = 30000)
    public void heartbeatBossProgress() {
        if (bossProgressEmitters.isEmpty()) return;
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : bossProgressEmitters) {
            try {
                emitter.send(SseEmitter.event().name("ping").data("keep-alive"));
            } catch (Exception e) {
                if (e instanceof AsyncRequestNotUsableException ||
                        e instanceof ClientAbortException ||
                        (e.getCause() instanceof ClientAbortException) ||
                        (e instanceof IOException && String.valueOf(e.getMessage()).contains("中止了一个已建立的连接"))) {
                    log.debug("Boss进度 SSE 客户端已断开（心跳），移除连接: {}", e.getMessage());
                    try { emitter.complete(); } catch (Exception ignored) {}
                } else {
                    log.error("发送Boss进度心跳失败", e);
                }
                deadEmitters.add(emitter);
            }
        }
        bossProgressEmitters.removeAll(deadEmitters);
    }
}