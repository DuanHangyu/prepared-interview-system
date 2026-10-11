# DESIGN.md

## Purpose

This document extracts the visual language from the supplied reference images:

- `/Users/duanhangyu/Downloads/20260112_695de0f5000000000e00cf13.png`
- `/Users/duanhangyu/Downloads/20260112_695de0f5000000000e00cf13 (1).png`

Use this as the design source of truth for the next student-side and teacher-side
UI refresh of the AI Interview System. This is not a login-page direction. It is
for post-login dashboards, assessment workflow pages, student progress pages,
teacher management pages, records, schedules, and report surfaces.

The target style is a bright education-SaaS dashboard: light blue workspace,
white modular cards, compact left navigation, blue step progress, soft data
widgets, and dense but friendly operational panels.

## Design DNA

- Light education SaaS
- Blue learning platform
- Clean B-side dashboard
- Rounded white app shell
- Compact left sidebar
- Step-based workflow navigation
- Soft blue content zones
- Card-based task and data modules
- Friendly data visualization
- Operational but approachable

The product should feel like a polished education/workforce platform rather
than a heavy admin console. It should remain efficient and repeatable for daily
use, but the tone should be lighter, clearer, and more supportive than the
current dark/cream student dashboard.

## Core Principles

### 1. App Shell Over Page Decoration

The reference images present the app as a large white rounded product shell on a
very pale background. The shell contains the sidebar and content, while the page
background stays quiet.

Apply this to the AI Interview System:

- Use one main app frame for student and teacher dashboards.
- Keep the frame white with subtle shadow and 18-24px outer radius.
- Use light gray-blue page background outside the frame.
- Optional dotted background is allowed outside the app frame only.
- Do not scatter decorative elements inside operational content.

### 2. Left Navigation Is Slim And Functional

The sidebar is narrow, white, and grouped. It uses small icons, compact labels,
and a strong blue active state.

Apply this to:

- student dashboard navigation
- teacher dashboard navigation
- records/settings/report side menus

Sidebar structure:

- product logo block
- user identity block
- grouped menu sections
- active nav item in bright blue
- bottom resources/help section

Avoid full-height charcoal sidebars for this design direction.

### 3. Blue Journey Bar As Page Context

The reference uses a top horizontal stepper with arrow-like segments. It gives
the page a sense of progression and makes the dashboard feel guided.

For this project, use step bars for workflows such as:

- student assessment lifecycle: `预约考核 -> 设备检测 -> 正式面试 -> 报告复盘`
- teacher assessment setup: `基础信息 -> 规则配置 -> 学生安排 -> 发布确认`
- record review: `筛选记录 -> 查看详情 -> 核分确认 -> 导出报告`

Active steps use a saturated blue gradient. Inactive steps use pale gray-blue.

### 4. Dashboard Cards Are Soft But Data-Dense

The visual language is card-based, but not empty. Each card has a clear job:
task, schedule, progress, rank, quick entry, profile, or form section.

Use cards for:

- upcoming assessments
- to-do items
- quick actions
- progress rings
- score summaries
- ranking / comparison blocks
- report status
- student profile
- teacher workload
- assessment setup sections

Do not use oversized hero banners. Every visible module should answer a useful
operational question.

### 5. Friendly Data Visualization

The references use progress rings, small rank cards, compact calendars, and
timeline rows. Data should feel motivating, not intimidating.

Use:

- blue circular progress rings
- blue/green progress bars
- small amber highlights for ranking or warning
- compact calendar blocks for appointments
- vertical timeline rows for daily tasks or assessment events
- small badges for status and counts

Avoid dense chart libraries unless needed. Prefer simple native UI widgets.

## Visual System

### Color Tokens

```css
:root {
  --page-bg: #f3f7fc;
  --page-dot: rgba(207, 218, 235, 0.42);

  --shell-bg: #ffffff;
  --shell-border: #e8f0fb;
  --section-bg: #f4f9ff;
  --section-blue: #eaf4ff;
  --section-blue-strong: #dcecff;

  --card-bg: #ffffff;
  --card-tint: #f8fbff;
  --card-border: #e3edf9;

  --primary: #1597ff;
  --primary-deep: #0078e7;
  --primary-soft: #dff0ff;
  --primary-glow: rgba(21, 151, 255, 0.22);

  --cyan: #42c9ff;
  --mint: #37c978;
  --amber: #ffb33f;
  --orange: #ff8a2a;

  --text-strong: #12233d;
  --text-main: #27364b;
  --text-muted: #6b7c93;
  --text-soft: #9aa9bc;

  --line: #dfeaf7;
  --line-soft: rgba(223, 234, 247, 0.72);
}
```

Color usage:

- Primary actions, active nav, and active stepper: `--primary`.
- Page and large sections: light blue-gray, not pure gray.
- Success/readiness: mint green.
- Ranking/attention/warning: amber or orange.
- Text: navy-charcoal, not pure black.
- Keep purple and heavy dark palettes out of the student/teacher dashboards.

### Background

Use a pale blue-gray background with optional very subtle dot pattern:

```css
.page-bg {
  background:
    radial-gradient(circle, var(--page-dot) 1px, transparent 1px),
    var(--page-bg);
  background-size: 28px 28px;
}
```

The dot pattern should be barely visible. It belongs to the outer page, not to
cards or tables.

### Typography

Use the existing Chinese-friendly UI stack:

```css
font-family:
  Inter,
  "SF Pro Display",
  "PingFang SC",
  "Microsoft YaHei",
  Arial,
  sans-serif;
```

Recommended scale:

- Page title: 24-30px, 800 weight
- Section title: 18-22px, 750-800 weight
- Card title: 15-17px, 700 weight
- Body text: 13-14px, 500-600 weight
- Metadata: 11-12px, 500 weight
- Metric number: 28-42px, 800-850 weight

Text should feel crisp and compact. Avoid display-scale typography inside
routine dashboard cards.

### Radius And Shadow

Use radius to make the interface friendly, but keep operational density.

```css
:root {
  --radius-shell: 22px;
  --radius-section: 14px;
  --radius-card: 8px;
  --radius-control: 8px;

  --shadow-shell: 0 24px 60px rgba(38, 64, 102, 0.12);
  --shadow-card: 0 8px 22px rgba(54, 97, 150, 0.08);
  --shadow-hover: 0 12px 30px rgba(21, 151, 255, 0.14);
}
```

Outer shells and big section containers may use larger radii. Repeated cards and
controls should stay closer to 8px so the UI remains mature.

### Icon Style

Use small line icons with filled blue square/circle backgrounds only for section
headers and quick actions.

Rules:

- Icons should be 14-18px in normal menu items.
- Section icon chips can be 24-32px.
- Active nav icon turns white on blue.
- Avoid oversized decorative icons.

## Layout Patterns

### Main App Shell

Desktop structure:

```css
.app-shell {
  max-width: 1320px;
  min-height: 760px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: 188px minmax(0, 1fr);
  border-radius: var(--radius-shell);
  background: var(--shell-bg);
  border: 1px solid var(--shell-border);
  box-shadow: var(--shadow-shell);
  overflow: hidden;
}

.app-sidebar {
  background: #ffffff;
  border-right: 1px solid var(--line-soft);
}

.app-main {
  background: #f7fbff;
  padding: 16px;
  min-width: 0;
}
```

Responsive:

- Tablet: sidebar becomes top segmented nav or collapsible rail.
- Mobile: one-column layout, no horizontal scroll.
- Cards stack vertically and preserve readable spacing.

### Top Stepper

Use for major flows. Shape may be a segmented horizontal rail.

```css
.flow-stepper {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.flow-step {
  min-height: 42px;
  padding: 0 14px;
  border-radius: 8px;
  background: #eef3f9;
  color: var(--text-muted);
}

.flow-step.is-active {
  background: linear-gradient(135deg, var(--primary), var(--primary-deep));
  color: #ffffff;
  box-shadow: 0 10px 22px var(--primary-glow);
}
```

### Section Bands

The reference often places multiple cards inside a pale blue section band. Use
this for dashboard groups instead of nesting decorative cards.

```css
.section-band {
  padding: 14px;
  border-radius: var(--radius-section);
  background: linear-gradient(180deg, #edf6ff, #f8fbff);
  border: 1px solid var(--card-border);
}
```

### Card Grid

```css
.card-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.dashboard-card {
  min-height: 88px;
  padding: 14px;
  border-radius: var(--radius-card);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  box-shadow: var(--shadow-card);
}
```

Use stable card heights for repeated content. Long Chinese titles should wrap
within the card rather than pushing controls out of alignment.

## Component Guidelines

### Sidebar

Sidebar anatomy:

1. logo and product name
2. user mini profile
3. optional visitor/class strip
4. grouped nav
5. bottom resource links

Active item:

- blue background
- white icon/text
- small sparkle or dot decoration allowed only inside active item

Inactive item:

- white/transparent background
- navy text
- low-contrast icon

### Quick Entry Cards

Use for shortcuts such as:

- 我的报告
- 日程安排
- 个人档案
- 设备检测
- 课后作业
- 新建考核
- 学生名单

Style:

- white card
- colored icon chip
- title below icon
- no paragraph text unless needed

### Progress Ring

The progress ring is a signature element in the reference.

Use it for:

- student interview readiness
- report completion
- assessment participation rate
- teacher grading progress
- course/assessment completion

Visual:

- blue primary arc
- optional green comparison arc
- large center percentage
- short label below

### Calendar And Timeline

Use a compact calendar on pages where time matters:

- appointment selection
- student schedule
- teacher upcoming interviews
- daily review tasks

Daily tasks should use a vertical timeline with colored dots, not a heavy table.

### Ranking / Comparison Card

The reference uses friendly ranking blocks with avatar medals. In this product,
use them carefully:

- student side: personal progress compared with class average
- teacher side: workload / completed reviews / pending reviews

Do not make assessment results feel like a public leaderboard unless the product
explicitly requires it.

### Tables

Teacher pages still need tables, but the table should inherit the soft style.

Rules:

- table sits in a white card or section band
- header row uses `#f5f9ff`
- row height 52-64px
- actions are compact blue text buttons or small primary buttons
- avoid large red/orange pills for every row
- pagination stays bottom-right

### Forms

Use light form sections rather than long plain forms.

- Group related settings into pale section bands.
- Use two- or three-column fields on desktop.
- Use small helper text for complex assessment rules.
- Primary submit button is blue.
- Dangerous actions use red only when destructive.

## Student Side Application

### Student Dashboard

Recommended modules:

- top flow stepper: appointment, device check, interview, report
- start class / current assessment area
- quick entry cards
- learning/interview progress ring
- current rank or personal progress comparison
- right-side calendar and today's tasks

Adapt labels to the AI Interview System:

- `开始上课` -> `开始面试`
- `下次上课` -> `下一场面试`
- `待办事项` -> `待完成任务`
- `最近上课` -> `最近考核`
- `学习情况` -> `面试准备度`

### Student Profile / Report

Use the second reference image's profile layout:

- left profile summary
- top journey stepper
- progress ring
- compact KPI cards
- small ranked or comparative widgets
- assessment/report completion cards

Replace learning metrics with interview-specific metrics:

- 已完成考核数
- 平均得分
- 设备检测通过率
- 报告生成进度
- 追问完成率
- 出勤/准时率

### Student Assessment Detail

Use section bands:

- assessment info
- appointment status
- device check
- question/answer summary
- score overview
- report recommendations

Avoid the current all-dark progress side card. Use blue progress and white cards.

## Teacher Side Application

### Teacher Home

Teacher home should become an operational cockpit:

- top flow: 考核配置 -> 学生安排 -> 面试进行 -> 结果复盘
- summary cards: 今日面试、待核分、已完成、异常任务
- calendar/timeline: upcoming interviews and deadlines
- quick entries: 新建考核、学生管理、考核记录、报告导出
- progress rings: grading progress / report completion

### Student Management

Use a soft table shell:

- filters in one pale band at top
- action buttons aligned right
- table with blue active focus, no heavy shadows
- import/export as secondary outlined buttons

### Assessment Settings

Use cards instead of a single long table:

- basic info card
- scoring rules card
- question settings card
- appointment settings card
- participating students card

Each card should have a blue section icon and compact fields.

### Assessment Records

Use table plus summary:

- summary strip: total records, average score, pending review, exported reports
- records table
- right-side or modal detail drawer for score breakdown
- export button in primary blue

## Motion And Interaction

Motion should be light and functional:

- hover card lifts 2-4px with blue shadow
- active nav slides or fades quickly
- progress rings animate once on entry
- stepper change uses 160-220ms transition
- avoid bouncy animation

Focus states:

- blue 2px focus outline
- accessible contrast
- visible keyboard focus for all buttons and form controls

## Do / Do Not

Do:

- Use a bright white app shell.
- Use pale blue section backgrounds.
- Make navigation compact and clear.
- Use blue as the primary active state.
- Show progress and next actions visibly.
- Prefer cards with specific operational meaning.
- Keep teacher pages dense but friendly.
- Keep student pages guided and encouraging.

Do not:

- Reuse the previous charcoal/cream dashboard style for this refresh.
- Turn dashboards into landing pages.
- Use oversized hero typography inside the app.
- Use decorative people illustrations or stock photos.
- Put poster decorations inside the real product frame.
- Make every card a different saturated color.
- Hide important actions in low-contrast text.
- Allow horizontal overflow on student pages.
- Let table actions be clipped on teacher pages.

## Implementation Notes For This Repo

This project uses Vue and Ant Design Vue. Prefer scoped wrappers and CSS custom
properties over one-off inline styles.

Suggested wrapper classes:

- `.edu-page`
- `.edu-shell`
- `.edu-sidebar`
- `.edu-main`
- `.edu-stepper`
- `.edu-section`
- `.edu-card`
- `.edu-stat-card`
- `.edu-progress-ring`
- `.edu-table-card`

When adapting Ant Design Vue:

- Override tokens at wrapper scope where possible.
- Keep table row heights predictable.
- Use `Button` primary for the strongest page action only.
- Use segmented controls or stepper tabs for workflow state.
- Use icon chips for quick entry cards.

## Migration Checklist

Before shipping a redesigned student or teacher page:

- The page uses the light blue/white app shell.
- The active route is obvious in the sidebar.
- The top context or workflow step is visible.
- Cards answer real product questions.
- Primary next action is visible without scrolling.
- Text fits at desktop, tablet, and mobile widths.
- No important table columns are clipped.
- No decorative background competes with content.
- Empty states are friendly but compact.
- The page still works for repeated daily operation.
