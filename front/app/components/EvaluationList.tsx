'use client'

export interface EvaluationItem {
  time: string
  jobName: string
  companyName: string
  salary: string
  stage: string
  reason: string
  score?: number | null
  greeting?: string | null
  action: string
}

/**
 * 根据处理结果返回标签样式。
 * 入参：action string，必填，处理结果文本。
 * 出参：string，Tailwind 类名。
 */
function actionClass(action: string): string {
  if (action.startsWith('已发送')) return 'bg-green-100 text-green-800'
  if (action === '测试通过未发送') return 'bg-blue-100 text-blue-800'
  if (action === '处理中') return 'bg-amber-100 text-amber-800'
  if (action === '发送失败' || action === 'AI不可用未发送') return 'bg-red-100 text-red-800'
  return 'bg-gray-100 text-gray-700'
}

/**
 * 逐岗位判定记录列表（最新在上）。
 * 入参：props.items EvaluationItem[]，必填，判定记录；props.title string，可选，标题，默认“判定记录”。
 * 出参：JSX.Element。
 */
export default function EvaluationList({ items, title = '判定记录' }: { items: EvaluationItem[]; title?: string }) {
  const list = [...items].reverse()
  const sent = items.filter(i => i.action.startsWith('已发送')).length
  const passed = items.filter(i => i.action === '测试通过未发送').length
  return (
    <div className="space-y-2">
      <p className="text-sm text-muted-foreground">
        {title}：共判定 {items.length} 个岗位，已发送 {sent} 个{passed > 0 ? `，测试通过未发送 ${passed} 个` : ''}
      </p>
      <div className="max-h-[420px] overflow-auto rounded-md border">
        <table className="w-full text-sm">
          <thead className="sticky top-0 bg-muted text-left">
            <tr>
              <th className="px-2 py-1.5 whitespace-nowrap">时间</th>
              <th className="px-2 py-1.5">岗位 / 公司</th>
              <th className="px-2 py-1.5 whitespace-nowrap">结果</th>
              <th className="px-2 py-1.5">环节与原因</th>
            </tr>
          </thead>
          <tbody>
            {list.map((item, idx) => (
              <tr key={`${item.time}-${idx}`} className="border-t align-top">
                <td className="px-2 py-1.5 whitespace-nowrap text-muted-foreground">{item.time}</td>
                <td className="px-2 py-1.5">
                  <div className="font-medium">{item.jobName || '（无名称）'}</div>
                  <div className="text-xs text-muted-foreground">{item.companyName} {item.salary}</div>
                </td>
                <td className="px-2 py-1.5 whitespace-nowrap">
                  <span className={`rounded px-1.5 py-0.5 text-xs ${actionClass(item.action)}`}>{item.action}</span>
                </td>
                <td className="px-2 py-1.5">
                  <div>
                    【{item.stage}
                    {item.score != null ? ` ${item.score}分` : ''}】{item.reason}
                  </div>
                  {item.greeting && <div className="mt-1 text-xs text-muted-foreground">招呼语：{item.greeting}</div>}
                </td>
              </tr>
            ))}
            {list.length === 0 && (
              <tr>
                <td colSpan={4} className="px-2 py-4 text-center text-muted-foreground">暂无记录</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
