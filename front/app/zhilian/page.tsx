'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import { createSSEWithBackoff } from '@/lib/sse'
import { BiLogOut, BiSave, BiBriefcase, BiPlay, BiStop, BiSync, BiTestTube, BiListCheck, BiMessageDetail } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import AnalysisContent from '@/app/zhilian/analysis/AnalysisContent'
import PageHeader from '@/app/components/PageHeader'
import { API_BASE } from '@/lib/api'
import MatchRuleCard, { MatchRuleCardHandle } from '@/app/components/MatchRuleCard'
import EvaluationList, { EvaluationItem } from '@/app/components/EvaluationList'

interface ZhilianConfig {
  id?: number
  keywords?: string
  cityCode?: string
  salary?: string
  /** 是否只搜索兼职/临时岗位：1 是 / 0 否，默认 1 */
  onlyPartTime?: number
  /** 投递后是否进入聊天发送招呼语：1 发送 / 0 不发送，默认 1 */
  sendGreeting?: number
  /** 投递后是否发送图片简历（Boss 页上传的图片）：1 发送 / 0 不发送，默认 1 */
  sendImgResume?: number
  /** 每日最多投递次数，0 表示不限，默认 20 */
  dailyGreetLimit?: number
}

/**
 * 开关按钮（样式与 Boss 页一致）。
 * 入参：props.id string，必填，元素 id；props.checked boolean，必填，是否开启；props.label string，必填，无障碍名称；
 *       props.onToggle () => void，必填，点击回调。
 * 出参：JSX.Element。
 */
function Switch({ id, checked, label, onToggle }: { id: string; checked: boolean; label: string; onToggle: () => void }) {
  return (
    <button
      id={id}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={onToggle}
      className={`relative inline-flex h-7 w-12 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 ${checked ? 'bg-primary' : 'bg-muted-foreground/40'}`}
    >
      <span aria-hidden="true" className={`pointer-events-none block h-5 w-5 rounded-full bg-background shadow-lg ring-0 transition-transform ${checked ? 'translate-x-5' : 'translate-x-0.5'}`} />
    </button>
  )
}

interface Option { name: string; code: string }
interface ZhilianOptions { city: Option[] }

export default function ZhilianPage() {
  // 岗位匹配规则卡片，保存配置 / 开始投递时一并保存规则修改
  const matchRuleRef = useRef<MatchRuleCardHandle>(null)
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [isDelivering, setIsDelivering] = useState(false)
  // 运行状态以后端 /api/zhilian/status 轮询结果为准
  const [stopping, setStopping] = useState(false)
  const [runMessage, setRunMessage] = useState('')
  const [checkingLogin, setCheckingLogin] = useState(true)
  const [showLogoutDialog, setShowLogoutDialog] = useState(false)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null)
  const [showLogoutResultDialog, setShowLogoutResultDialog] = useState(false)
  const [logoutResult, setLogoutResult] = useState<{ success: boolean; message: string } | null>(null)
  const [backendAvailable, setBackendAvailable] = useState(true)
  const [runMode, setRunMode] = useState('')
  const [evaluations, setEvaluations] = useState<EvaluationItem[]>([])
  const [lastResult, setLastResult] = useState<{ mode?: string; success?: boolean; message?: string; finishedAt?: string } | null>(null)
  const [todayGreetings, setTodayGreetings] = useState<number | null>(null)
  const [showTestDialog, setShowTestDialog] = useState(false)
  const [testStarting, setTestStarting] = useState(false)

  const [config, setConfig] = useState<ZhilianConfig>({ keywords: '', cityCode: '', salary: '' })
  const [options, setOptions] = useState<ZhilianOptions>({ city: [] })
  const [loadingConfig, setLoadingConfig] = useState(true)

  useEffect(() => {
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
      console.warn('[智联招聘] EventSource 不可用，无法连接SSE')
      setCheckingLogin(false)
      return
    }

    const client = createSSEWithBackoff(`${API_BASE}/api/jobs/login-status/stream`, {
      onOpen: () => console.log('[智联招聘 SSE] 连接已打开'),
      onError: (e, attempt, delay) => {
        console.warn(`[智联招聘 SSE] 连接错误，第${attempt}次重连，延迟 ${delay}ms`, e)
        setCheckingLogin(false)
      },
      listeners: [
        {
          name: 'connected',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              console.log('[智联招聘 SSE] connected事件数据:', data)
              console.log('[智联招聘 SSE] zhilianLoggedIn状态:', data.zhilianLoggedIn)
              setIsLoggedIn(data.zhilianLoggedIn || false)
              setCheckingLogin(false)
            } catch (error) {
              console.error('[智联招聘 SSE] 解析连接消息失败:', error)
            }
          },
        },
        {
          name: 'login-status',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              console.log('[智联招聘 SSE] login-status事件数据:', data)
              if (data.platform === 'zhilian') {
                console.log('[智联招聘 SSE] 智联登录状态变更:', data.isLoggedIn)
                setIsLoggedIn(data.isLoggedIn)
                setCheckingLogin(false)
              }
            } catch (error) {
              console.error('[智联招聘 SSE] 解析登录状态消息失败:', error)
            }
          },
        },
        { name: 'ping', handler: () => {} },
      ],
    })

    return () => client.close()
  }, [])

  // 与猎聘一致的关键词解析/序列化
  const parseKeywordsFromDb = (raw?: string): string => {
    if (!raw) return ''
    const t = raw.trim()
    if (t.startsWith('[') && t.endsWith(']')) {
      try {
        const arr = JSON.parse(t)
        if (Array.isArray(arr)) return arr.filter(Boolean).join(', ')
      } catch (e) {
        console.warn('[智联] 解析关键词JSON失败，使用原值:', e)
      }
    }
    return t.replace(/，/g, ',')
  }

  const serializeKeywordsForDb = (display?: string): string => {
    const raw = (display || '').trim()
    if (!raw) return '[]'
    const norm = raw.replace(/，/g, ',')
    const tokens = norm
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0)
    return JSON.stringify(tokens)
  }

  const fetchAllData = async () => {
    try {
      const res = await fetch(`${API_BASE}/api/zhilian/config`)
      const data = await res.json()
      if (data.config) {
        const normalized = { ...data.config }
        normalized.keywords = parseKeywordsFromDb(data.config.keywords)
        setConfig(normalized)
      }
      if (data.options) setOptions(data.options)
    } catch (e) {
      console.error('[智联] 获取配置失败:', e)
    } finally {
      setLoadingConfig(false)
    }
  }

  useEffect(() => { fetchAllData() }, [])

  /**
   * 从后端读取任务真实状态，同步按钮与最近进度。
   * 入参：无。出参：Promise<void>。
   */
  const syncRunStatus = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/api/zhilian/status`)
      if (!res.ok) return
      const data = await res.json()
      setIsDelivering(!!data.isRunning)
      setStopping(!!data.stopping)
      setRunMessage(data.lastMessage || '')
      setRunMode(data.runMode || '')
      setEvaluations(Array.isArray(data.evaluations) ? data.evaluations : [])
      setTodayGreetings(typeof data.todayGreetings === 'number' ? data.todayGreetings : null)
      setLastResult(data.lastResult && Object.keys(data.lastResult).length > 0 ? data.lastResult : null)
    } catch {
      // 网络抖动时保持当前显示
    }
  }, [])

  useEffect(() => {
    syncRunStatus()
    const timer = setInterval(syncRunStatus, 3000)
    return () => clearInterval(timer)
  }, [syncRunStatus])

  /**
   * 从 Boss 配置同步关键词与城市到智联，并刷新表单。
   * 入参：无。出参：Promise<void>。
   */
  const handleSyncFromBoss = async () => {
    try {
      const res = await fetch(`${API_BASE}/api/zhilian/syncFromBoss`, { method: 'POST' })
      const data = await res.json()
      await fetchAllData()
      setSaveResult({ success: !!data.success, message: data.message || (data.success ? '同步完成' : '同步失败') })
    } catch {
      setSaveResult({ success: false, message: '同步失败：网络或服务异常。' })
    }
    setShowSaveDialog(true)
  }

  // 探测后端可用性（与 51job 保持一致风格）
  useEffect(() => {
    (async () => {
      try {
        const res = await fetch(`${API_BASE}/api/zhilian/config`, { method: 'GET' })
        const ok = !!res && res.ok
        setBackendAvailable(ok)
        if (ok) {
          await fetchAllData()
        } else {
          setLoadingConfig(false)
        }
      } catch (e) {
        setBackendAvailable(false)
        setLoadingConfig(false)
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /**
   * 启动智联投递；岗位匹配规则有未保存修改时先保存，保存失败不启动。
   * 入参：无。出参：Promise<void>。
   */
  const handleStartDelivery = async () => {
    const ruleResult = await matchRuleRef.current?.save({ onlyIfDirty: true })
    if (ruleResult && !ruleResult.ok) {
      setSaveResult({ success: false, message: `启动失败：${ruleResult.message}` })
      setShowSaveDialog(true)
      return
    }
    try {
      setIsDelivering(true)
      const response = await fetch(`${API_BASE}/api/zhilian/start`, { method: 'POST' })
      const data = await response.json()
      if (!data.success) {
        setIsDelivering(false)
        setSaveResult({ success: false, message: data.message || '启动失败' })
        setShowSaveDialog(true)
      }
    } catch (error) {
      setIsDelivering(false)
    } finally {
      syncRunStatus()
    }
  }

  const handleStopDelivery = async () => {
    try {
      setStopping(true)
      await fetch(`${API_BASE}/api/zhilian/stop`, { method: 'POST' })
    } catch (error) {
    } finally {
      syncRunStatus()
    }
  }

  const triggerLogout = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/zhilian/logout`, { method: 'POST' })
      const data = await response.json()
      setIsLoggedIn(false)
      setLogoutResult({ success: data.success, message: data.success ? '已退出登录，Cookie已清空。' : data.message })
      setShowLogoutResultDialog(true)
    } catch (error) {
      setLogoutResult({ success: false, message: '退出登录失败：网络或服务异常。' })
      setShowLogoutResultDialog(true)
    }
  }

  const handleSaveCookie = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/cookie/save?platform=zhilian`, { method: 'POST' })
      const data = await response.json()
      setSaveResult({ success: data.success, message: data.success ? '配置保存成功。' : data.message })
      setShowSaveDialog(true)
    } catch (error) {
      setSaveResult({ success: false, message: '配置保存失败：网络或服务异常。' })
      setShowSaveDialog(true)
    }
  }

  /**
   * 保存智联配置；岗位匹配规则有未保存修改时先一并保存。
   * 入参：silent boolean，可选，默认 false，true 时不弹结果框。
   * 出参：Promise<boolean>，规则与配置都保存成功为 true。
   */
  const handleSaveConfig = async (silent: boolean = false): Promise<boolean> => {
    const show = (success: boolean, message: string) => {
      if (silent) return
      setSaveResult({ success, message })
      setShowSaveDialog(true)
    }
    const ruleResult = await matchRuleRef.current?.save({ onlyIfDirty: true })
    if (ruleResult && !ruleResult.ok) {
      show(false, `保存失败：${ruleResult.message}`)
      return false
    }
    const ruleSaved = !!ruleResult && !ruleResult.skipped
    try {
      const payload = { ...config, keywords: serializeKeywordsForDb(config.keywords) }
      const response = await fetch(`${API_BASE}/api/zhilian/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      if (!response.ok) {
        show(false, '保存失败：后端返回异常状态。')
        return false
      }
      try { await fetch(`${API_BASE}/api/cookie/save?platform=zhilian`, { method: 'POST' }) } catch {}
      await fetchAllData()
      show(true, ruleSaved ? '保存成功，配置与岗位匹配规则已更新。' : '保存成功，配置已更新。')
      return true
    } catch (error) {
      console.error('[智联] 保存配置失败:', error)
      show(false, '保存失败：网络或服务异常。')
      return false
    }
  }

  /**
   * 启动单次测试：先保存当前配置与匹配规则，再按所选模式执行一次。
   * 入参：dryRun boolean，必填，true 只评估不投递，false 对找到的第一个匹配岗位真实投递并打招呼。
   * 出参：Promise<void>。
   */
  const handleTestDelivery = async (dryRun: boolean) => {
    setTestStarting(true)
    try {
      if (!(await handleSaveConfig(true))) {
        setSaveResult({ success: false, message: '测试未启动：保存配置或岗位匹配规则失败，请检查后重试' })
        setShowSaveDialog(true)
        return
      }
      const response = await fetch(`${API_BASE}/api/zhilian/testDelivery?dryRun=${dryRun}`, { method: 'POST' })
      const data = await response.json()
      if (!data.success) {
        setSaveResult({ success: false, message: `测试未启动：${data.message || '启动失败，请查看后端日志'}` })
        setShowSaveDialog(true)
      }
      setShowTestDialog(false)
      syncRunStatus()
    } catch {
      setSaveResult({ success: false, message: `测试未启动：无法连接后端服务（${API_BASE}）` })
      setShowSaveDialog(true)
    } finally {
      setTestStarting(false)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiBriefcase className="text-2xl" />}
        title="智联招聘配置"
        subtitle="配置智联招聘平台的求职参数"
        iconClass="text-white"
        accentBgClass="bg-purple-500"
        actions={
          <div className="flex items-center gap-2">
            {checkingLogin ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiPlay className="mr-1" /> 检查登录中...
              </Button>
            ) : !isLoggedIn ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiPlay className="mr-1" /> 请先登录智联招聘
              </Button>
            ) : isDelivering && stopping ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiStop className="mr-1" /> 停止中…
              </Button>
            ) : isDelivering ? (
              <Button onClick={handleStopDelivery} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <BiStop className="mr-1" /> 停止投递
              </Button>
            ) : (
              <Button onClick={handleStartDelivery} size="sm" className="rounded-full bg-gradient-to-r from-teal-500 to-green-500 hover:from-teal-600 hover:to-green-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <BiPlay className="mr-1" /> 开始投递
              </Button>
            )}
            {!checkingLogin && isLoggedIn && !isDelivering && (
              <Button onClick={() => setShowTestDialog(true)} size="sm" className="rounded-full bg-gradient-to-r from-amber-500 to-orange-500 hover:from-amber-600 hover:to-orange-600 text-white px-4 shadow-lg">
                <BiTestTube className="mr-1" /> 单次测试
              </Button>
            )}
            <Button onClick={() => setShowLogoutDialog(true)} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-pink-500 hover:from-red-600 hover:to-pink-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
              <BiLogOut className="mr-1" /> 退出登录
            </Button>
            <Button onClick={handleSyncFromBoss} size="sm" disabled={isDelivering} className="rounded-full bg-gradient-to-r from-amber-500 to-orange-500 text-white px-4 shadow-lg">
              <BiSync className="mr-1" /> 从Boss同步
            </Button>
            <Button onClick={() => handleSaveConfig(false)} size="sm" className="rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
              <BiSave className="mr-1" /> 保存配置
            </Button>
          </div>
        }
      />

      <Tabs defaultValue="config" className="w-full">
        <TabsList className="grid w-full grid-cols-2">
          <TabsTrigger value="config">平台配置</TabsTrigger>
          <TabsTrigger value="analytics">投递分析</TabsTrigger>
        </TabsList>

        <TabsContent value="config" className="space-y-6 mt-6">
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiBriefcase className="text-primary" />
                智联招聘平台说明
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                <p className="text-sm text-muted-foreground">请在浏览器标签页中登录智联招聘平台，登录成功后系统会自动检测登录状态。</p>
                <p className="text-sm text-muted-foreground">登录成功后，点击“开始投递”按钮启动自动投递任务。</p>
                <p className="text-sm text-muted-foreground">点击“保存配置”保存本页配置、岗位匹配规则与登录 Cookie；开始投递前也会自动保存未保存的匹配规则。</p>
                <p className="text-sm text-muted-foreground">每个岗位先按下方“岗位匹配规则”检查名称，再读取岗位详情交给 AI 复核，通过后点击“立即投递”（智联会同时发送在线简历和账号里设置的默认招呼语），再进入聊天发送匹配判定给出的招呼语和图片简历。“从Boss同步”会把 Boss 的关键词和城市同步过来，匹配规则两个平台本身就是同一份。</p>
                {runMessage && <p className="text-sm text-foreground">最近进度：{runMessage}</p>}
              </div>
            </CardContent>
          </Card>

          {/* 任务状态与判定记录：数据来自后端轮询 */}
          {(isDelivering || evaluations.length > 0 || lastResult) && (
            <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <BiListCheck className="text-primary" />
                  {runMode === 'normal' ? '投递任务' : runMode ? '单次测试' : '任务'}状态
                </CardTitle>
                <CardDescription>
                  {isDelivering
                    ? `运行中（${runMode === 'test' ? '单次测试：真实投递 1 个' : runMode === 'testDry' ? '单次测试：只评估不投递' : '正式投递'}）：${runMessage}`
                    : lastResult
                      ? `最近一次${lastResult.mode === 'normal' ? '投递' : '测试'}于 ${lastResult.finishedAt || ''} 结束：${lastResult.message || ''}`
                      : '当前没有运行中的任务'}
                </CardDescription>
              </CardHeader>
              <CardContent>
                <EvaluationList items={evaluations} />
              </CardContent>
            </Card>
          )}

          <MatchRuleCard ref={matchRuleRef} />

          {/* 投递后行为 */}
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiMessageDetail className="text-primary" />
                投递后行为
              </CardTitle>
              <CardDescription>固定招呼语、AI 招呼语开关与图片简历与 Boss 共用：在 Boss 页「投递行为」与「AI配置」页维护。保存后下次启动投递生效。</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex flex-col gap-4 rounded-lg border bg-muted/20 p-4 sm:flex-row sm:items-center sm:justify-between">
                <div className="space-y-1">
                  <Label htmlFor="zlSendGreeting" className="text-base font-medium">投递后发送招呼语</Label>
                  <p className="text-sm text-muted-foreground">开启后，投递成功即点「继续沟通」进入聊天，核对会话岗位一致后发送匹配判定给出的招呼语；关闭时只投递，HR 只收到智联账号的默认招呼语。</p>
                </div>
                <Switch id="zlSendGreeting" label="投递后发送招呼语" checked={config.sendGreeting !== 0}
                  onToggle={() => setConfig(c => ({ ...c, sendGreeting: c.sendGreeting === 0 ? 1 : 0 }))} />
              </div>
              <div className="flex flex-col gap-4 rounded-lg border bg-muted/20 p-4 sm:flex-row sm:items-center sm:justify-between">
                <div className="space-y-1">
                  <Label htmlFor="zlSendImgResume" className="text-base font-medium">投递后发送图片简历</Label>
                  <p className="text-sm text-muted-foreground">开启后，在聊天中按顺序发送 Boss 页上传的全部图片（智联聊天只支持图片，不支持文件）；招呼语发送失败时不发图片。</p>
                </div>
                <Switch id="zlSendImgResume" label="投递后发送图片简历" checked={config.sendImgResume !== 0}
                  onToggle={() => setConfig(c => ({ ...c, sendImgResume: c.sendImgResume === 0 ? 1 : 0 }))} />
              </div>
              <div className="flex flex-col gap-4 rounded-lg border bg-muted/20 p-4 sm:flex-row sm:items-center sm:justify-between">
                <div className="space-y-1">
                  <Label htmlFor="zlDailyGreetLimit" className="text-base font-medium">每日最多投递次数</Label>
                  <p className="text-sm text-muted-foreground">按真实投递次数统计，当天达到上限后任务自动停止，次日自动恢复；填 0 表示不限。今日已投递：{todayGreetings ?? '—'} 次。</p>
                </div>
                <Input id="zlDailyGreetLimit" type="number" min={0} className="w-28" value={config.dailyGreetLimit ?? 20}
                  onChange={e => setConfig(c => ({ ...c, dailyGreetLimit: e.target.value === '' ? 0 : Math.max(0, Number(e.target.value)) }))} />
              </div>
            </CardContent>
          </Card>

          {/* 配置表单 */}
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiBriefcase className="text-primary" />
                配置参数
              </CardTitle>
            </CardHeader>
            <CardContent>
              {loadingConfig ? (
                <p className="text-sm text-muted-foreground">配置加载中...</p>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="md:col-span-2 flex flex-col gap-4 rounded-lg border bg-muted/20 p-4 sm:flex-row sm:items-center sm:justify-between">
                    <div className="space-y-1">
                      <Label htmlFor="zlOnlyPartTime" className="text-base font-medium">只搜兼职/临时岗位</Label>
                      <p className="text-sm text-muted-foreground">开启后搜索时带上智联「工作性质：兼职/临时」筛选；关闭时搜索全部工作性质，坐班全职岗位会由 AI 复核过滤。</p>
                    </div>
                    <Switch id="zlOnlyPartTime" label="只搜兼职/临时岗位" checked={config.onlyPartTime !== 0}
                      onToggle={() => setConfig(c => ({ ...c, onlyPartTime: c.onlyPartTime === 0 ? 1 : 0 }))} />
                  </div>
                  <div className="space-y-2">
                    <Label>搜索关键词（逗号分隔）</Label>
                    <Input
                      placeholder="如：Java, 后端, Spring"
                      value={config.keywords || ''}
                      onChange={(e) => setConfig((c) => ({ ...c, keywords: e.target.value }))}
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>城市</Label>
                    <Select
                      value={config.cityCode || ''}
                      onChange={(e) => setConfig((c) => ({ ...c, cityCode: e.target.value }))}
                      placeholder="请选择城市"
                    >
                      {options.city.map((o) => (
                        <option key={o.code} value={o.code}>{o.name}</option>
                      ))}
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>薪资范围（最低和最高工资，用逗号分割）</Label>
                    <Input
                      placeholder="如：12000, 20000 或 不限"
                      value={config.salary || ''}
                      onChange={(e) => setConfig((c) => ({ ...c, salary: e.target.value }))}
                    />
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="analytics" className="space-y-6 mt-6">
          <AnalysisContent />
        </TabsContent>
      </Tabs>

      {/* 单次测试弹框 */}
      {showTestDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-md border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <BiTestTube className="text-amber-500" /> 单次测试
              </CardTitle>
              <CardDescription>
                先保存当前页面配置，再执行一次投递流程：逐个判定岗位，找到 1 个通过判定的岗位即结束，最多判定 40 个岗位。判定结果显示在“任务状态”卡片中。
              </CardDescription>
            </CardHeader>
            <CardContent className="pt-0 space-y-3">
              <Button disabled={testStarting} onClick={() => handleTestDelivery(true)} className="w-full justify-start h-auto whitespace-normal py-2 text-left" variant="outline">
                只评估不投递：走完判定并确认“立即投递”可用，不点投递、不发任何消息
              </Button>
              <Button disabled={testStarting} onClick={() => handleTestDelivery(false)} className="w-full justify-start h-auto whitespace-normal py-2 text-left bg-gradient-to-r from-amber-500 to-orange-500 text-white">
                真实投递 1 个：对找到的第 1 个匹配岗位投递，并发送招呼语和图片简历
              </Button>
              <div className="flex justify-end">
                <Button variant="ghost" onClick={() => setShowTestDialog(false)} className="rounded-full px-4">取消</Button>
              </div>
            </CardContent>
          </Card>
        </div>
      )}

      {/* 退出确认弹框 */}
      {showLogoutDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <BiLogOut className="text-red-500" /> 确认退出登录
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">退出后将清除Cookie并切换为未登录状态。</p>
              <div className="flex justify-end gap-2">
                <Button variant="ghost" onClick={() => setShowLogoutDialog(false)} className="rounded-full px-4">取消</Button>
                <Button onClick={async () => { await triggerLogout(); setShowLogoutDialog(false) }} className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 text-white px-4">确认退出</Button>
              </div>
            </CardContent>
          </Card>
        </div>
      )}

      {/* 退出登录结果弹框 */}
      {showLogoutResultDialog && logoutResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <BiLogOut className={logoutResult.success ? 'text-green-500' : 'text-red-500'} />
                {logoutResult.success ? '退出登录成功' : '退出登录失败'}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">{logoutResult.message}</p>
              <Button onClick={() => setShowLogoutResultDialog(false)} className={`rounded-full px-4 ${logoutResult.success ? 'bg-green-500' : 'bg-red-500'} text-white`}>知道了</Button>
            </CardContent>
          </Card>
        </div>
      )}

      {/* 保存Cookie结果弹框 */}
      {showSaveDialog && saveResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <BiSave className={saveResult.success ? 'text-green-500' : 'text-red-500'} />
                {saveResult.success ? '保存成功' : '保存失败'}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">{saveResult.message}</p>
              <Button onClick={() => setShowSaveDialog(false)} className={`rounded-full px-4 ${saveResult.success ? 'bg-green-500' : 'bg-red-500'} text-white`}>知道了</Button>
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  )
}
