#!/usr/bin/env python3
"""现有题库按年级/单元分类脚本。

规则（用户要求：无法分辨单元的题目直接删除）：
- 数学 → 人教版四年级上册单元（含 2026 秋新版"位置与方向"），按知识点关键词匹配；
  匹配到四下内容（小数、运算律、三角形、观察物体、鸡兔同笼等）→ 删除。
- 语文 → 统编版四年级上册第 1-8 单元，按课文锚点关键词匹配；通用题/锚点不明 → 删除。
- 英语 → 教科版 2024 新教材 U1-U8（来源标签已知）；期中/期末综合卷 → 删除。

输出：seed_classified.json（不覆盖任何现有 seed 文件）
"""
import json, os, sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(BASE, "app")

MATH_UNITS = [
    ("第2单元 公顷和平方千米", ["公顷", "平方千米"]),
    ("第7单元 条形统计图", ["条形统计图", "统计表", "复式", "直条", "格代表", "纵轴", "横轴", "图例"]),
    ("第8单元 数学广角—优化", ["烙饼", "煎鱼", "田忌", "赛马", "沏茶", "合理安排", "最优", "等候时间", "三局两胜"]),
    ("位置与方向(新课标)", ["上北下南", "东北方向", "东南方向", "西北方向", "西南方向", "正东", "正南", "正北", "正西", "观察点", "参照点", "藏宝", "寻宝", "朝东", "朝北", "点钟方向", "向北走", "向南走", "向东走", "向西走", "西北角"]),
    ("第3单元 角的度量", ["量角器", "射线", "直线", "线段", "锐角", "钝角", "平角", "周角", "直角", "∠", "刻度", "角的度数", "度", "角的边", "角的顶点", "转过的角"]),
    ("第1单元 大数的认识", ["亿", "万位", "数位", "计数单位", "读作", "四舍五入", "省略万", "改写成", "自然数", "计算器", "千万", "十万", "八位数", "六位数", "七位数", "九位数", "十进制", "数级", "1亿", "零", "最高位", "个级"]),
    ("第5单元 平行四边形和梯形", ["平行四边形", "梯形", "平行线", "垂线", "互相垂直", "互相平行", "同一平面", "等腰梯形", "直角梯形", "高", "底"]),
    ("第6单元 除数是两位数的除法", ["除以", "除数", "被除数", "商", "余数", "试商", "÷"]),
    ("第4单元 三位数乘两位数", ["乘", "因数", "积", "单价", "总价", "速度", "×"]),
]
MATH_DELETE_KEYWORDS = [
    # 四下及超纲内容
    "小数", "运算律", "结合律", "分配律", "交换律", "三角形", "内角", "观察物体", "正方体",
    "鸡兔", "方程", "平均数", "条形统计图之外的学生身高", "计数单位是 0.01", "0.1 相比",
]

CN_UNITS = [
    ("第1单元", ["观潮", "钱塘江", "潮头", "走月亮", "繁星", "巴金"]),
    ("第2单元", ["蝙蝠", "雷达", "超声波", "无线电", "蝴蝶的家", "提问"]),
    ("第3单元", ["爬山虎", "蟋蟀", "住宅", "法布尔", "暮江吟", "题西林壁", "雪梅", "庐山", "梅须逊雪", "连续观察", "观察日记"]),
    ("第4单元", ["盘古", "精卫", "普罗米修斯", "女娲", "神话", "火种", "锁在悬崖", "溺而不返"]),
    ("第5单元", ["麻雀", "天都峰", "老麻雀", "猎狗", "写清楚"]),
    ("第6单元", ["批注", "牛和鹅", "陀螺", "窝囊", "大老虎"]),
    ("第7单元", ["周恩来", "中华之崛起", "梅兰芳", "蓄须", "出塞", "凉州词", "夏日绝句", "李清照", "王昌龄", "高适", "延安", "飞将"]),
    ("第8单元", ["王戎", "道旁李", "西门豹", "扁鹊", "纪昌", "历史人物", "书信", "写信", "称呼", "署名", "问候语"]),
]

EN_UNIT_NAMES = {
    1: "U1 Come on In",
    2: "U2 Help Yourself",
    3: "U3 I'm the Chef Today",
    4: "U4 Help Out in the Kitchen",
    5: "U5 She Helps Me a Lot",
    6: "U6 I Love My Family",
    7: "U7 Family Time",
    8: "U8 Joy in the Air",
}

def classify_math(q):
    text = q["question"] + q.get("explanation", "")
    for kw in MATH_DELETE_KEYWORDS:
        if kw in text:
            return None, "四下/超纲内容"
    for unit, kws in MATH_UNITS:
        hits = [k for k in kws if k in text]
        if hits:
            return unit, None
    return None, "无法判断单元"

def classify_cn(q):
    text = q["question"] + q.get("explanation", "")
    hits = []
    for unit, kws in CN_UNITS:
        for k in kws:
            if k in text:
                hits.append((unit, k))
    if not hits:
        return None, "无课文锚点(通用题)"
    from collections import Counter
    counter = Counter(u for u, _ in hits)
    return counter.most_common(1)[0][0], None

def classify_en(q):
    grade = q.get("grade", "")
    m = re.search(r"U(\d)", grade)
    if m:
        n = int(m.group(1))
        return EN_UNIT_NAMES.get(n, f"U{n}"), None
    if "期中" in grade or "期末" in grade:
        return None, "期中期末综合卷无法定单元"
    return None, "无法判断单元"

import re  # noqa: E402

def main():
    sys.path.insert(0, BASE)
    from app.seed_data import SEED_QUESTIONS
    src = []
    for q in SEED_QUESTIONS:
        q = dict(q)
        q["_src"] = "自编"
        src.append(q)
    for name in ("seed_grade4_pohw.json", "seed_english_gz_term1.json"):
        p = os.path.join(APP, name)
        if os.path.exists(p):
            for q in json.load(open(p, encoding="utf-8")):
                q = dict(q)
                q["_src"] = name
                src.append(q)

    out, removed = [], []
    seen_texts = set()
    for q in src:
        subject = q["subject"]
        q_text = str(q.get("question", "")).strip()
        if q_text in seen_texts:
            removed.append({"subject": subject, "question": q_text[:60], "reason": "与其他题完全重复"})
            continue
        seen_texts.add(q_text)
        if subject == "数学":
            unit, reason = classify_math(q)
        elif subject == "语文":
            unit, reason = classify_cn(q)
        else:
            unit, reason = classify_en(q)
        if unit is None:
            removed.append({"subject": subject, "question": q["question"][:60], "reason": reason})
            continue
        item = {
            "subject": subject,
            "grade": q.get("grade", "四年级上"),
            "unit": unit,
            "question": q["question"],
            "options": q["options"],
            "correct_index": q["correct_index"],
            "explanation": q.get("explanation", ""),
            "enabled": True,
        }
        out.append(item)

    out_path = os.path.join(APP, "seed_classified.json")
    json.dump(out, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    from collections import Counter
    print(f"原题库: {len(src)} 题 → 分类保留: {len(out)} 题, 删除: {len(removed)} 题")
    print("\n按 学科×单元 分布:")
    for (s, u), n in sorted(Counter((q["subject"], q["unit"]) for q in out).items()):
        print(f"  {s} | {u}: {n}")
    print("\n删除原因分布:")
    for r, n in Counter(x["reason"] for x in removed).items():
        print(f"  {r}: {n}")
    rm_path = os.path.join(BASE, "removed_questions_report.json")
    json.dump(removed, open(rm_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"\n已生成: {out_path}")
    print(f"删除清单: {os.path.abspath(rm_path)}")

if __name__ == "__main__":
    main()
