// 受限解码 PPT 生成脚本 v3
// 信息图风格：彩色圆角节点 + 箭头流程 + 虚线框分组
// 修正：语法约束 = Lark EBNF + Outlines + llguidance（非 JSON Schema）
// 修复：所有颜色使用6位格式；调整布局避免重叠和溢出

const PptxGenJS = require('pptxgenjs');
const pres = new PptxGenJS();

// ============================================================
// SLIDE DIMENSIONS
// ============================================================
pres.layout = 'LAYOUT_16x9';
const SW = 10;
const SH = 5.625;
const MX = 0.5;
const MY = 0.3;
const CW = SW - 2 * MX;
const CH = SH - 2 * MY;

// ============================================================
// DESIGN TOKENS (all 6-digit hex)
// ============================================================
const C = {
  ink: '222222',
  muted: '777777',
  subtle: 'AAAAAA',
  white: 'FFFFFF',
  bg: 'FFFFFF',
  // semantic
  ok: '2E7D32',
  okBg: 'E8F5E9',
  okLine: '81C784',
  bad: 'C62828',
  badBg: 'FFEBEE',
  badLine: 'EF9A9A',
  // node palette
  green:  { fill: 'D4EDDA', line: '82C997', text: '155724' },
  blue:   { fill: 'D1ECF1', line: '7FC5D4', text: '0C5460' },
  purple: { fill: 'E2D5F2', line: 'B39DDB', text: '4A235A' },
  yellow: { fill: 'FFF3CD', line: 'E6C65C', text: '856404' },
  orange: { fill: 'FFE0B2', line: 'FFB74D', text: 'E65100' },
  teal:   { fill: 'C8E6C9', line: '81C784', text: '1B5E20' },
  gray:   { fill: 'F5F5F5', line: 'DDDDDD', text: '666666' },
};

// ============================================================
// HELPERS
// ============================================================

function iconNode(slide, x, y, w, h, colorKey, icon, title, desc) {
  const c = C[colorKey] || C.blue;
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x, y, w, h,
    fill: { color: c.fill },
    line: { color: c.line, width: 2 },
    rectRadius: 0.12,
    shadow: { type: 'outer', color: '999999', blur: 4, offset: 2, angle: 90, opacity: 0.25 },
  });
  slide.addText(icon, {
    x: x + 0.12, y: y + 0.06, w: 0.55, h: h - 0.12,
    fontSize: 24,
    align: 'center',
    valign: 'middle',
    margin: 0,
  });
  slide.addText(title, {
    x: x + 0.75, y: y + 0.06, w: w - 0.9, h: h * 0.48,
    fontSize: 13,
    bold: true,
    color: c.text,
    valign: 'bottom',
    margin: 0,
    fit: 'shrink',
  });
  if (desc) {
    slide.addText(desc, {
      x: x + 0.75, y: y + h * 0.55, w: w - 0.9, h: h * 0.4,
      fontSize: 10,
      color: c.text,
      valign: 'top',
      margin: 0,
      fit: 'shrink',
    });
  }
}

function arrowDown(slide, cx, y, size = 0.22, color = '777777') {
  slide.addShape(pres.shapes.RECTANGLE, {
    x: cx - 0.012, y: y - 0.04, w: 0.024, h: size * 0.6,
    fill: { color },
    line: { color, width: 0 },
  });
  slide.addShape(pres.shapes.ISOSCELES_TRIANGLE, {
    x: cx - size * 0.45, y: y + size * 0.4, w: size * 0.9, h: size * 0.5,
    fill: { color },
    line: { color, width: 0 },
    flipV: true,
  });
}

function dashedBox(slide, x, y, w, h, title, color) {
  color = color || '888888';
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x, y, w, h,
    fill: { color: 'F9F9F9', opacity: 0.4 },
    line: { color, width: 2, dashType: 'dash' },
    rectRadius: 0.12,
  });
  const tw = Math.min(title.length * 0.14 + 0.4, w * 0.55);
  const tx = x + (w - tw) / 2;
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x: tx, y: y - 0.17, w: tw, h: 0.34,
    fill: { color: C.white },
    line: { color, width: 1.5 },
    rectRadius: 0.08,
  });
  slide.addText(title, {
    x: tx, y: y - 0.17, w: tw, h: 0.34,
    fontSize: 11,
    bold: true,
    color: '555555',
    align: 'center',
    valign: 'middle',
    margin: 0,
    fit: 'shrink',
  });
}

function badge(slide, x, y, w, h, text, colorKey) {
  const c = C[colorKey] || C.blue;
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x, y, w, h,
    fill: { color: c.fill },
    line: { color: c.line, width: 1 },
    rectRadius: h / 2,
  });
  slide.addText(text, {
    x, y, w, h,
    fontSize: 10.5,
    bold: true,
    color: c.text,
    align: 'center',
    valign: 'middle',
    margin: 0,
    fit: 'shrink',
  });
}

function circle(slide, x, y, size, text, colorKey, active) {
  const c = C[colorKey] || C.blue;
  slide.addShape(pres.shapes.OVAL, {
    x, y, w: size, h: size,
    fill: { color: active ? c.fill : C.white },
    line: { color: c.line, width: active ? 3 : 2 },
  });
  slide.addText(text, {
    x, y, w: size, h: size,
    fontSize: 10,
    bold: true,
    color: c.text,
    align: 'center',
    valign: 'middle',
    margin: 0,
    fit: 'shrink',
  });
}

function title(slide, t, sub) {
  slide.addText(t, {
    x: MX, y: MY, w: CW, h: 0.6,
    fontSize: 26,
    bold: true,
    color: C.ink,
    align: 'center',
    charSpacing: 1.2,
    margin: 0,
    fit: 'shrink',
  });
  if (sub) {
    slide.addText(sub, {
      x: MX, y: MY + 0.62, w: CW, h: 0.28,
      fontSize: 11,
      color: C.muted,
      align: 'center',
      margin: 0,
      fit: 'shrink',
    });
  }
}

function pageno(slide, n, t) {
  slide.addText(n + ' / ' + t, {
    x: SW - 0.9, y: SH - 0.3, w: 0.5, h: 0.2,
    fontSize: 9,
    color: C.subtle,
    align: 'right',
    margin: 0,
    fit: 'shrink',
  });
}

// ============================================================
// SLIDE 1: 封面
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.white };

  s.addShape(pres.shapes.RECTANGLE, {
    x: 0, y: 0, w: SW, h: 0.1,
    fill: { color: 'FFB74D' },
    line: { color: 'FFB74D', width: 0 },
  });

  s.addText('受限解码', {
    x: 0, y: 1.6, w: SW, h: 1.1,
    fontSize: 52,
    bold: true,
    color: C.ink,
    align: 'center',
    charSpacing: 4,
    margin: 0,
    fit: 'shrink',
  });

  s.addText('语法约束 · 语义约束 · 两阶段渐进式生成', {
    x: 0, y: 2.9, w: SW, h: 0.45,
    fontSize: 17,
    color: C.muted,
    align: 'center',
    margin: 0,
    fit: 'shrink',
  });

  // 三个图标
  const iy = 3.7;
  const is_ = 1.0;
  const ig = 0.55;
  const itw = is_ * 3 + ig * 2;
  const isx = (SW - itw) / 2;
  const items = [
    { c: 'green',  icon: '📐', label: 'EBNF 语法' },
    { c: 'blue',   icon: '🔄', label: 'FSM 约束' },
    { c: 'purple', icon: '🎯', label: '语义保证' },
  ];
  items.forEach((it, i) => {
    const ix = isx + i * (is_ + ig);
    circle(s, ix, iy, is_, '', it.c, true);
    s.addText(it.icon, {
      x: ix, y: iy + 0.1, w: is_, h: is_ * 0.5,
      fontSize: 28,
      align: 'center',
      valign: 'middle',
      margin: 0,
    });
    s.addText(it.label, {
      x: ix, y: iy + is_ - 0.26, w: is_, h: 0.22,
      fontSize: 10.5,
      bold: true,
      color: C[it.c].text,
      align: 'center',
      margin: 0,
      fit: 'shrink',
    });
  });

  s.addShape(pres.shapes.RECTANGLE, {
    x: 0, y: SH - 0.08, w: SW, h: 0.08,
    fill: { color: '81C784' },
    line: { color: '81C784', width: 0 },
  });
}

// ============================================================
// SLIDE 2: 语法约束解码
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.white };

  title(s, '语法约束解码', 'Lark EBNF 语法 · Outlines + llguidance · 逐 Token 保证 AADL 语法正确');

  // 顶部徽章
  const by = 1.05;
  const bw = 1.7;
  const bh = 0.34;
  const bg2 = 0.25;
  const btw = bw * 3 + bg2 * 2;
  const bsx = (SW - btw) / 2;

  badge(s, bsx, by, bw, bh, '📐 EBNF 文法', 'blue');
  badge(s, bsx + bw + bg2, by, bw, bh, '🔄 CFG 状态机', 'purple');
  badge(s, bsx + 2 * (bw + bg2), by, bw, bh, '🎯 Logits 过滤', 'orange');

  // === 左侧主流程 ===
  const fx = 0.7;
  const fw = 4.2;
  const fy0 = 1.55;
  const nh = 0.52;   // 节点高度
  const ah = 0.2;    // 箭头高度
  const ag = 0.04;   // 箭头间距
  let cy = fy0;

  // 节点1：自然语言需求
  iconNode(s, fx, cy, fw, nh, 'green', '📝',
    '自然语言需求', '"生成飞行控制系统 AADL 代码"');
  cy += nh + 0.02;
  arrowDown(s, fx + fw / 2, cy, ah);
  cy += ah + ag;

  // 节点2：Lark EBNF 语法
  iconNode(s, fx, cy, fw, nh, 'blue', '📜',
    'Lark EBNF 语法文件', 'aadl_core.lark — 定义 AADL 文法规则');
  cy += nh + 0.02;
  arrowDown(s, fx + fw / 2, cy, ah);
  cy += ah + ag;

  // 虚线框：Outlines + llguidance
  const boxH = 1.15;
  dashedBox(s, fx, cy, fw, boxH, '🤖 Outlines + llguidance 约束解码', '888888');

  // 框内两个卡片
  const cw = (fw - 0.6) / 2;
  const ch = 0.65;
  const ciy = cy + 0.28;

  // 卡1：LLM 生成
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x: fx + 0.2, y: ciy, w: cw, h: ch,
    fill: { color: C.white },
    line: { color: 'CCCCCC', width: 1 },
    rectRadius: 0.08,
  });
  s.addText('🧠 LLM 生成', {
    x: fx + 0.25, y: ciy + 0.05, w: cw - 0.1, h: 0.28,
    fontSize: 11.5,
    bold: true,
    color: '333333',
    align: 'center',
    margin: 0,
    fit: 'shrink',
  });
  s.addText('输出候选 Token logits', {
    x: fx + 0.25, y: ciy + 0.33, w: cw - 0.1, h: 0.24,
    fontSize: 9.5,
    color: '666666',
    align: 'center',
    margin: 0,
    fit: 'shrink',
  });

  // 加号
  s.addText('+', {
    x: fx + fw / 2 - 0.1, y: ciy + 0.1, w: 0.2, h: ch - 0.2,
    fontSize: 18,
    bold: true,
    color: 'AAAAAA',
    align: 'center',
    valign: 'middle',
    margin: 0,
  });

  // 卡2：CFG 约束
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x: fx + fw - cw - 0.2, y: ciy, w: cw, h: ch,
    fill: { color: C.white },
    line: { color: 'CCCCCC', width: 1 },
    rectRadius: 0.08,
  });
  s.addText('🔒 CFG 约束', {
    x: fx + fw - cw - 0.15, y: ciy + 0.05, w: cw - 0.1, h: 0.28,
    fontSize: 11.5,
    bold: true,
    color: '333333',
    align: 'center',
    margin: 0,
    fit: 'shrink',
  });
  s.addText('只放行语法合法 Token', {
    x: fx + fw - cw - 0.15, y: ciy + 0.33, w: cw - 0.1, h: 0.24,
    fontSize: 9.5,
    color: '666666',
    align: 'center',
    margin: 0,
    fit: 'shrink',
  });

  cy += boxH + 0.02;
  arrowDown(s, fx + fw / 2, cy, ah);
  cy += ah + ag;

  // 节点4：AADL 代码输出
  iconNode(s, fx, cy, fw, nh, 'purple', '📄',
    'AADL 代码', '100% 语法正确，结构合法');
  // 节点底部: cy + nh = 4.99 + 0.52 = 5.51 < SH-MY=5.325? 不对
  // 让我重新算: fy0=1.55
  // n1: 1.55+0.52=2.07, arrow space 0.22+0.04=0.26 → 2.33
  // n2: 2.33+0.52=2.85, arrow space 0.26 → 3.11
  // box: 3.11+1.15=4.26, arrow space 0.26 → 4.52
  // n4: 4.52+0.52=5.04 → 5.04 < 5.325 ✓ 有空间

  // === 右侧 ===
  const rx = 5.4;
  const rw = CW - (rx - MX);  // 9 - 4.9 = 4.1
  let ry = fy0;

  // FSM 原理
  const fsmH = 1.2;
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x: rx, y: ry, w: rw, h: fsmH,
    fill: { color: 'F9F9F9' },
    line: { color: 'DDDDDD', width: 2 },
    rectRadius: 0.1,
  });
  s.addText('🔄 CFG 前缀自动机原理', {
    x: rx, y: ry + 0.08, w: rw, h: 0.3,
    fontSize: 12.5,
    bold: true,
    color: '333333',
    align: 'center',
    margin: 0,
    fit: 'shrink',
  });

  const states = ['S0', 'S1', 'S2', 'S3', '✓'];
  const ss = 0.38;
  const sg = 0.06;
  const ssw = ss * states.length + sg * (states.length - 1);
  const ssx = rx + (rw - ssw) / 2;
  const ssy = ry + 0.45;
  states.forEach((st, i) => {
    const sx = ssx + i * (ss + sg);
    const active = i === 0;
    circle(s, sx, ssy, ss, st, 'blue', active);
    if (i < states.length - 1) {
      s.addText('→', {
        x: sx + ss - 0.02, y: ssy + 0.04, w: sg + 0.04, h: ss - 0.08,
        fontSize: 12,
        color: '999999',
        align: 'center',
        valign: 'middle',
        margin: 0,
      });
    }
  });

  s.addText('每生成一个 Token，状态机前进一步\n只允许语法合法的 Token 被选中', {
    x: rx + 0.15, y: ry + fsmH - 0.4, w: rw - 0.3, h: 0.32,
    fontSize: 9,
    color: '888888',
    align: 'center',
    margin: 0,
    fit: 'shrink',
  });

  ry += fsmH + 0.18;

  // 能保证
  const okH = 0.95;
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x: rx, y: ry, w: rw, h: okH,
    fill: { color: C.okBg },
    line: { color: C.okLine, width: 2 },
    rectRadius: 0.1,
  });
  s.addText('✅ 语法约束能保证', {
    x: rx + 0.15, y: ry + 0.05, w: rw - 0.3, h: 0.26,
    fontSize: 11.5,
    bold: true,
    color: C.ok,
    margin: 0,
    fit: 'shrink',
  });
  s.addText(
    '• 关键字、标识符、字面量格式正确\n' +
    '• 组件 / 端口 / 连接结构完整\n' +
    '• 括号配对、分号结尾等格式正确',
    {
    x: rx + 0.2, y: ry + 0.34, w: rw - 0.4, h: okH - 0.4,
    fontSize: 9.5,
    color: C.ok,
    margin: 0,
    fit: 'shrink',
  });

  ry += okH + 0.16;

  // 不能保证
  const noH = 0.95;
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x: rx, y: ry, w: rw, h: noH,
    fill: { color: C.badBg },
    line: { color: C.badLine, width: 2 },
    rectRadius: 0.1,
  });
  s.addText('❌ 语法约束不能保证', {
    x: rx + 0.15, y: ry + 0.05, w: rw - 0.3, h: 0.26,
    fontSize: 11.5,
    bold: true,
    color: C.bad,
    margin: 0,
    fit: 'shrink',
  });
  s.addText(
    '• 子组件类别符合包含规则\n' +
    '• 分类器引用真实存在\n' +
    '• 连接端口方向 / 类型匹配',
    {
    x: rx + 0.2, y: ry + 0.34, w: rw - 0.4, h: noH - 0.4,
    fontSize: 9.5,
    color: C.bad,
    margin: 0,
    fit: 'shrink',
  });
  // 右侧底部: ry + noH = 1.55+1.2+0.18+0.95+0.16+0.95 = 4.99 < 5.325 ✓

  pageno(s, '1', '2');
}

// ============================================================
// SLIDE 3: 语义约束解码
// ============================================================
{
  const s = pres.addSlide();
  s.background = { color: C.white };

  title(s, '语义约束解码', '三阶段渐进式生成 · 动态 Schema 枚举约束 · 生成时即保证语义正确');

  // 核心原理说明条
  const py = 1.05;
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x: MX, y: py, w: CW, h: 0.45,
    fill: { color: 'FFF8E1' },
    line: { color: 'FFB74D', width: 2 },
    rectRadius: 0.08,
  });
  s.addText('💡  核心思想：每阶段 Schema 基于前一阶段结果动态构建，把语义约束编码进 enum，约束解码让模型只能选合法值', {
    x: MX + 0.15, y: py + 0.04, w: CW - 0.3, h: 0.37,
    fontSize: 11.5,
    bold: true,
    color: 'E65100',
    align: 'center',
    valign: 'middle',
    margin: 0,
    fit: 'shrink',
  });

  // === 三个阶段卡片 ===
  const sy = 1.65;
  const sh = 2.7;
  const sw = 2.85;
  const sg = 0.35;
  const tsw = sw * 3 + sg * 2;
  const ssx = (SW - tsw) / 2;

  const phases = [
    {
      num: '1',
      title: '阶段一',
      subtitle: '包 + 类型 + 实现骨架',
      color: '1976D2',
      bg: 'E3F2FD',
      border: '42A5F5',
      what: '生成所有组件类型和实现骨架',
      constraints: [
        { icon: '📦', text: 'type_components + impl_components 分离' },
        { icon: '🏷️', text: 'category 限定枚举值' },
        { icon: '🔗', text: 'base_type 引用类型名' },
        { icon: '🔌', text: '端口方向/类型枚举约束' },
      ],
      output: '输出：完整类型定义 + 空实现骨架',
    },
    {
      num: '2',
      title: '阶段二',
      subtitle: '子组件（分类器约束）',
      color: '7B1FA2',
      bg: 'F3E5F5',
      border: 'AB47BC',
      what: '为每个实现填充子组件',
      constraints: [
        { icon: '📂', text: '子组件按类别分组（systems/threads/...）' },
        { icon: '✅', text: '父类别决定允许的子组件类别 → 包含规则' },
        { icon: '🔗', text: 'classifier 枚举 = 对应类别类型名 → 引用完整' },
      ],
      output: '输出：所有实现的子组件实例',
    },
    {
      num: '3',
      title: '阶段三',
      subtitle: '连接 + 绑定 + 流',
      color: '2E7D32',
      bg: 'E8F5E9',
      border: '66BB6A',
      what: '填充连接、绑定、端到端流',
      constraints: [
        { icon: '🔌', text: '连接按数据类型分组 → 类型匹配' },
        { icon: '↔️', text: 'source 选 out 端口，target 选 in 端口 → 方向匹配' },
        { icon: '🚌', text: 'bound_to_bus 枚举 = bus 子组件 → 合法绑定' },
        { icon: '🎯', text: 'binding target 枚举 = 子组件名 → 合法目标' },
      ],
      output: '输出：完整的 AADL 架构 JSON',
    },
  ];

  phases.forEach((phase, idx) => {
    const x = ssx + idx * (sw + sg);

    // 卡片背景
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
      x, y: sy, w: sw, h: sh,
      fill: { color: phase.bg },
      line: { color: phase.border, width: 2 },
      rectRadius: 0.12,
      shadow: { type: 'outer', color: '999999', blur: 5, offset: 2, angle: 90, opacity: 0.2 },
    });

    // 阶段数字徽章
    const ns = 0.48;
    s.addShape(pres.shapes.OVAL, {
      x: x + sw / 2 - ns / 2, y: sy - ns / 2 + 0.08, w: ns, h: ns,
      fill: { color: phase.border },
      line: { color: phase.color, width: 2 },
    });
    s.addText(phase.num, {
      x: x + sw / 2 - ns / 2, y: sy - ns / 2 + 0.08, w: ns, h: ns,
      fontSize: 18,
      bold: true,
      color: 'FFFFFF',
      align: 'center',
      valign: 'middle',
      margin: 0,
      fit: 'shrink',
    });

    // 标题
    s.addText(phase.title, {
      x: x + 0.1, y: sy + 0.3, w: sw - 0.2, h: 0.3,
      fontSize: 14,
      bold: true,
      color: phase.color,
      align: 'center',
      margin: 0,
      fit: 'shrink',
    });
    s.addText(phase.subtitle, {
      x: x + 0.1, y: sy + 0.58, w: sw - 0.2, h: 0.24,
      fontSize: 10,
      color: '666666',
      align: 'center',
      margin: 0,
      fit: 'shrink',
    });

    // 分隔线
    s.addShape(pres.shapes.RECTANGLE, {
      x: x + 0.3, y: sy + 0.88, w: sw - 0.6, h: 0.015,
      fill: { color: phase.border, opacity: 0.4 },
      line: { color: phase.border, width: 0, opacity: 0.4 },
    });

    // "做什么"
    s.addText('🔹 ' + phase.what, {
      x: x + 0.15, y: sy + 0.95, w: sw - 0.3, h: 0.22,
      fontSize: 10,
      bold: true,
      color: phase.color,
      margin: 0,
      fit: 'shrink',
    });

    // 约束列表
    let iy = sy + 1.2;
    const ih = 0.3;
    const ig = 0.04;
    phase.constraints.forEach(c => {
      s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
        x: x + 0.12, y: iy, w: sw - 0.24, h: ih,
        fill: { color: 'FFFFFF', opacity: 0.85 },
        line: { color: phase.border, opacity: 0.5, width: 1 },
        rectRadius: 0.05,
      });
      s.addText(c.icon, {
        x: x + 0.18, y: iy, w: 0.28, h: ih,
        fontSize: 11,
        align: 'center',
        valign: 'middle',
        margin: 0,
      });
      s.addText(c.text, {
        x: x + 0.48, y: iy, w: sw - 0.6, h: ih,
        fontSize: 8.5,
        color: '333333',
        valign: 'middle',
        margin: 0,
        fit: 'shrink',
      });
      iy += ih + ig;
    });

    // 输出行
    const oy = sy + sh - 0.32;
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
      x: x + 0.12, y: oy, w: sw - 0.24, h: 0.26,
      fill: { color: phase.color, opacity: 0.12 },
      line: { color: phase.border, width: 1 },
      rectRadius: 0.05,
    });
    s.addText('📤 ' + phase.output, {
      x: x + 0.18, y: oy, w: sw - 0.36, h: 0.26,
      fontSize: 8.5,
      bold: true,
      color: phase.color,
      valign: 'middle',
      margin: 0,
      fit: 'shrink',
    });

    // 阶段间箭头
    if (idx < phases.length - 1) {
      const ax = x + sw + sg / 2 - 0.14;
      const ay = sy + sh / 2 - 0.18;
      s.addText('➜', {
        x: ax, y: ay, w: 0.28, h: 0.36,
        fontSize: 22,
        color: phase.border,
        align: 'center',
        valign: 'middle',
        margin: 0,
      });
    }
  });

  // 底部结论
  const cy = sy + sh + 0.18;
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x: MX, y: cy, w: CW, h: 0.42,
    fill: { color: C.okBg },
    line: { color: C.okLine, width: 2 },
    rectRadius: 0.08,
    shadow: { type: 'outer', color: '999999', blur: 4, offset: 2, angle: 90, opacity: 0.15 },
  });
  s.addText('🏆  语义约束编码进动态 Schema + 约束解码 = 生成时即保证语义正确，无需后置校验', {
    x: MX + 0.2, y: cy + 0.05, w: CW - 0.4, h: 0.32,
    fontSize: 12.5,
    bold: true,
    color: C.ok,
    align: 'center',
    valign: 'middle',
    margin: 0,
    fit: 'shrink',
  });

  pageno(s, '2', '2');
}

// ============================================================
// SAVE
// ============================================================
pres.writeFile({ fileName: 'constrained_decoding_v4.pptx' }).then(() => {
  console.log('✅ PPT 生成完成: constrained_decoding_v4.pptx');
}).catch(err => {
  console.error('❌ 生成失败:', err);
  process.exit(1);
});
