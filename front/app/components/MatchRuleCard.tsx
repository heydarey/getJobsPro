'use client'

import { forwardRef, useEffect, useImperativeHandle, useState } from 'react'
import { BiShieldQuarter, BiSave, BiTestTube } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { API_BASE } from '@/lib/api'

interface MatchRule {
  titleIncludeKeywords: string[]
  titleExcludeKeywords: string[]
  minScore: number
  greetingForbiddenWords: string[]
  jobRequirement: string
}

interface EvaluateResult {
  pass: boolean
  stage: string
  reason: string
  score?: number | null
  greeting?: string | null
}

/**
 * 把逗号 / 顿号 / 换行分隔的文本拆成词列表。
 * 入参：text string，必填，用户输入文本。
 * 出参：string[]，去空白、去空项后的词列表。
 */
function splitWords(text: string): string[] {
  return text
    .split(/[,，、\n]/)
    .map(s => s.trim())
    .filter(Boolean)
}

/** 规则保存结果 */
export interface MatchRuleSaveResult {
  /** 是否成功；没有未保存修改时也为 true */
  ok: boolean
  /** 是否因没有修改而跳过保存，默认 false */
  skipped: boolean
  /** 结果说明，用于弹框或卡片内提示 */
  message: string
}

/** 父页面通过 ref 调用的方法 */
export interface MatchRuleCardHandle {
  /**
   * 保存规则。
   * 入参：options.onlyIfDirty boolean，可选，true 时没有未保存修改直接跳过；默认 false。
   * 出参：Promise<MatchRuleSaveResult>。
   */
  save: (options?: { onlyIfDirty?: boolean }) => Promise<MatchRuleSaveResult>
  /**
   * 是否有未保存的修改。
   * 入参：无。出参：boolean。
   */
  isDirty: () => boolean
}

/**
 * 把规则序列化为对比用的字符串，用于判断表单是否有未保存的修改。
 * 入参：rule MatchRule，必填。
 * 出参：string，JSON 文本。
 */
function ruleKey(rule: MatchRule): string {
  return JSON.stringify([
    rule.titleIncludeKeywords,
    rule.titleExcludeKeywords,
    rule.greetingForbiddenWords,
    rule.minScore,
    (rule.jobRequirement || '').trim(),
  ])
}

/**
 * 岗位匹配规则卡片（Boss / 智联共用同一份规则）。
 * 父页面可通过 ref 调用 save / isDirty，页面顶部「保存配置」、开始投递、单次测试时一并保存规则修改。
 * 入参：props.sayHi string，可选，试判定时使用的固定招呼语；默认空串。ref MatchRuleCardHandle，可选。
 * 出参：JSX.Element，规则编辑卡片。
 */
const MatchRuleCard = forwardRef<MatchRuleCardHandle, { sayHi?: string }>(function MatchRuleCard({ sayHi = '' }, ref) {
  const [includeText, setIncludeText] = useState('')
  const [excludeText, setExcludeText] = useState('')
  const [forbiddenText, setForbiddenText] = useState('')
  const [minScore, setMinScore] = useState('85')
  const [requirement, setRequirement] = useState('')
  // 最近一次从后端读到的规则快照，用于判断是否有未保存修改；null 表示尚未读取成功
  const [savedKey, setSavedKey] = useState<string | null>(null)
  const [message, setMessage] = useState<{ success: boolean; text: string } | null>(null)
  const [saving, setSaving] = useState(false)
  const [tryName, setTryName] = useState('')
  const [tryDesc, setTryDesc] = useState('')
  const [trying, setTrying] = useState(false)
  const [tryResult, setTryResult] = useState<EvaluateResult | null>(null)

  /**
   * 从后端加载规则并填入表单。
   * 入参：无。出参：Promise<void>。
   */
  const load = async () => {
    try {
      const res = await fetch(`${API_BASE}/api/matchRule`)
      const rule: MatchRule = await res.json()
      const include = rule.titleIncludeKeywords || []
      const exclude = rule.titleExcludeKeywords || []
      const forbidden = rule.greetingForbiddenWords || []
      const score = rule.minScore ?? 85
      setIncludeText(include.join('，'))
      setExcludeText(exclude.join('，'))
      setForbiddenText(forbidden.join('，'))
      setMinScore(String(score))
      setRequirement(rule.jobRequirement || '')
      setSavedKey(ruleKey({
        titleIncludeKeywords: include,
        titleExcludeKeywords: exclude,
        greetingForbiddenWords: forbidden,
        minScore: score,
        jobRequirement: rule.jobRequirement || '',
      }))
    } catch {
      setMessage({ success: false, text: '读取匹配规则失败，请确认后端已启动' })
    }
  }

  useEffect(() => {
    load()
  }, [])

  /**
   * 按当前表单内容组装规则。
   * 入参：无。出参：MatchRule。
   */
  const buildRule = (): MatchRule => ({
    titleIncludeKeywords: splitWords(includeText),
    titleExcludeKeywords: splitWords(excludeText),
    greetingForbiddenWords: splitWords(forbiddenText),
    minScore: Number(minScore),
    jobRequirement: requirement,
  })

  // 尚未成功读取规则时不算有修改，避免把空表单覆盖到后端
  const dirty = savedKey !== null && ruleKey(buildRule()) !== savedKey

  /**
   * 保存规则。
   * 入参：options.onlyIfDirty boolean，可选，true 时没有未保存修改直接跳过；默认 false。
   * 出参：Promise<MatchRuleSaveResult>。
   */
  const save = async (options?: { onlyIfDirty?: boolean }): Promise<MatchRuleSaveResult> => {
    if (savedKey === null) {
      const text = '匹配规则尚未读取成功，未保存'
      setMessage({ success: false, text })
      return { ok: false, skipped: false, message: text }
    }
    if (options?.onlyIfDirty && !dirty) {
      return { ok: true, skipped: true, message: '' }
    }
    setSaving(true)
    setMessage(null)
    try {
      const res = await fetch(`${API_BASE}/api/matchRule`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildRule()),
      })
      const data = await res.json()
      const ok = !!data.success
      const text = data.message || (ok ? '匹配规则已保存' : '匹配规则保存失败')
      setMessage({ success: ok, text })
      if (ok) await load()
      return { ok, skipped: false, message: text }
    } catch {
      const text = '匹配规则保存失败：网络或服务异常'
      setMessage({ success: false, text })
      return { ok: false, skipped: false, message: text }
    } finally {
      setSaving(false)
    }
  }

  useImperativeHandle(ref, () => ({ save, isDirty: () => dirty }))

  /**
   * 用粘贴的岗位信息试跑判定（不发送任何消息）。
   * 入参：无。出参：Promise<void>。
   */
  const tryEvaluate = async () => {
    setTrying(true)
    setTryResult(null)
    try {
      const res = await fetch(`${API_BASE}/api/matchRule/evaluate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ jobName: tryName, description: tryDesc, sayHi }),
      })
      setTryResult(await res.json())
    } catch {
      setTryResult({ pass: false, stage: '请求失败', reason: '网络或服务异常' })
    } finally {
      setTrying(false)
    }
  }

  return (
    <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700 border-amber-300">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <BiShieldQuarter className="text-amber-600" />
          岗位匹配规则（Boss / 智联共用）
        </CardTitle>
        <CardDescription>
          每个岗位发送前依次检查：岗位信息完整 → 名称必含词 → 名称排除词 → AI 严格复核（分数不低于阈值）→ 招呼语禁用词。任一环节不通过或出错都不发送。
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid gap-4 md:grid-cols-2">
          <div className="space-y-2">
            <Label htmlFor="titleInclude">岗位名称必含词（至少命中一个）</Label>
            <Textarea id="titleInclude" rows={3} value={includeText} onChange={e => setIncludeText(e.target.value)} placeholder="用逗号或换行分隔，例如：前端，Vue，React" />
          </div>
          <div className="space-y-2">
            <Label htmlFor="titleExclude">岗位名称排除词（命中任一即不发送）</Label>
            <Textarea id="titleExclude" rows={3} value={excludeText} onChange={e => setExcludeText(e.target.value)} placeholder="用逗号或换行分隔，例如：设计，测试，运营" />
          </div>
        </div>
        <div className="space-y-2">
          <Label htmlFor="jobRequirement">求职要求（交给 AI 逐条核对）</Label>
          <Textarea id="jobRequirement" rows={3} value={requirement} onChange={e => setRequirement(e.target.value)} placeholder="例如：只找前端开发方向的远程兼职；要求驻场、坐班或只招全职的一律不匹配" />
        </div>
        <div className="grid gap-4 md:grid-cols-[160px_minmax(0,1fr)]">
          <div className="space-y-2">
            <Label htmlFor="minScore">AI 匹配分阈值（0~100）</Label>
            <Input id="minScore" type="number" min={0} max={100} value={minScore} onChange={e => setMinScore(e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="greetingForbidden">招呼语禁用词（AI 招呼语或固定招呼语命中即不发送）</Label>
            <Input id="greetingForbidden" value={forbiddenText} onChange={e => setForbiddenText(e.target.value)} placeholder="例如：全栈" />
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <Button type="button" onClick={() => save()} disabled={saving}>
            <BiSave className="mr-1" /> {saving ? '保存中…' : '保存匹配规则'}
          </Button>
          {dirty && !saving && <span className="text-sm text-amber-600">有未保存的修改，点「保存匹配规则」或页面右上角「保存配置」后生效</span>}
          {!dirty && message && <span className={`text-sm ${message.success ? 'text-green-600' : 'text-red-600'}`}>{message.text}</span>}
        </div>

        <div className="space-y-2 rounded-lg border bg-muted/20 p-4">
          <Label className="text-base font-medium">粘贴岗位试判定（不发送任何消息）</Label>
          <Input value={tryName} onChange={e => setTryName(e.target.value)} placeholder="岗位名称" />
          <Textarea rows={4} value={tryDesc} onChange={e => setTryDesc(e.target.value)} placeholder="岗位描述" />
          <Button type="button" variant="outline" onClick={tryEvaluate} disabled={trying || !tryName.trim() || !tryDesc.trim()}>
            <BiTestTube className="mr-1" /> {trying ? '判定中…' : '试判定'}
          </Button>
          {tryResult && (
            <div className={`rounded-md border p-3 text-sm ${tryResult.pass ? 'border-green-300 bg-green-50 text-green-800' : 'border-red-300 bg-red-50 text-red-800'}`}>
              <p className="font-medium">
                {tryResult.pass ? '通过，会发送' : '不通过，不会发送'}（环节：{tryResult.stage}
                {tryResult.score != null ? `，分数：${tryResult.score}` : ''}）
              </p>
              <p className="mt-1">{tryResult.reason}</p>
              {tryResult.greeting && <p className="mt-1">招呼语：{tryResult.greeting}</p>}
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  )
})

export default MatchRuleCard
