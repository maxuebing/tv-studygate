"""题库单元分类规则（供数据库回填、配置页与分类脚本共用）。

分类依据：
- 数学：人教版四年级上册单元知识点关键词；四下/超纲内容单独识别。
- 语文：统编版四年级上册第 1-8 单元课文锚点。
- 英语：教科版（广州版）2024 新教材 U1-U8，依据来源 grade 标签。
"""

from typing import Any, Dict, Optional
import re

MATH_UNITS = [
    ("第2单元 公顷和平方千米", ["公顷", "平方千米"]),
    ("第7单元 条形统计图", ["条形统计图", "统计表", "复式", "直条", "格代表", "纵轴", "横轴", "图例"]),
    ("第8单元 数学广角—优化", ["烙饼", "煎鱼", "田忌", "赛马", "沏茶", "合理安排", "最优", "等候时间", "三局两胜"]),
    ("位置与方向(新课标)", ["上北下南", "东北方向", "东南方向", "西北方向", "西南方向", "正东", "正南", "正北", "正西", "观察点", "参照点", "藏宝", "寻宝", "朝东", "朝北", "点钟方向", "向北走", "向南走", "向东走", "向西走", "西北角"]),
    ("第3单元 角的度量", ["量角器", "射线", "直线", "线段", "锐角", "钝角", "平角", "周角", "直角", "∠", "刻度", "角的度数", "度", "角的边", "角的顶点", "转过的角"]),
    ("第1单元 大数的认识", ["亿", "万位", "数位", "计数单位", "读作", "四舍五入", "省略万", "改写成", "自然数", "计算器", "千万", "十万", "八位数", "六位数", "七位数", "九位数", "十进制", "数级", "1亿", "最高位", "个级", "近似数"]),
    ("第5单元 平行四边形和梯形", ["平行四边形", "梯形", "平行线", "垂线", "互相垂直", "互相平行", "同一平面", "等腰梯形", "直角梯形"]),
    ("第6单元 除数是两位数的除法", ["除以", "除数", "被除数", "试商", "÷"]),
    ("第4单元 三位数乘两位数", ["乘", "因数", "积", "单价", "总价", "速度", "×"]),
]

MATH_OUT_OF_SCOPE = [
    "小数", "运算律", "结合律", "分配律", "交换律", "三角形", "内角",
    "观察物体", "正方体", "鸡兔", "方程", "平均数",
]

CN_UNITS = [
    ("第1单元", ["观潮", "钱塘江", "潮头", "走月亮", "繁星", "巴金"]),
    ("第2单元", ["蝙蝠", "雷达", "超声波", "无线电波", "蝴蝶的家"]),
    ("第3单元", ["爬山虎", "蟋蟀", "住宅", "法布尔", "暮江吟", "题西林壁", "雪梅", "庐山", "梅须逊雪", "连续观察", "观察日记", "露似真珠"]),
    ("第4单元", ["盘古", "精卫", "普罗米修斯", "女娲", "神话", "火种", "锁在悬崖", "溺而不返"]),
    ("第5单元", ["麻雀", "天都峰", "老麻雀", "猎狗", "爬天都峰"]),
    ("第6单元", ["批注", "牛和鹅", "陀螺", "窝囊", "大老虎", "人不可貌相"]),
    ("第7单元", ["周恩来", "中华之崛起", "梅兰芳", "蓄须", "出塞", "凉州词", "夏日绝句", "李清照", "王昌龄", "高适", "延安", "飞将", "别董大"]),
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

UNCLASSIFIED = "未分类"


def classify_question(item: Dict[str, Any]) -> str:
    """判断单题所属单元；无法判断时返回 '未分类'。

    Args:
        item: 至少包含 subject、question 的题目字典，可含 grade/explanation/unit。

    Returns:
        单元名称字符串。
    """
    existing = str(item.get("unit") or "").strip()
    if existing and existing != UNCLASSIFIED:
        return existing

    subject = item.get("subject", "")
    text = str(item.get("question", "")) + " " + str(item.get("explanation", ""))

    if subject == "数学":
        for kw in MATH_OUT_OF_SCOPE:
            if kw in text:
                return UNCLASSIFIED
        for unit, kws in MATH_UNITS:
            if any(k in text for k in kws):
                return unit
        return UNCLASSIFIED

    if subject == "语文":
        hits = {}
        for unit, kws in CN_UNITS:
            count = sum(1 for k in kws if k in text)
            if count:
                hits[unit] = count
        if not hits:
            return UNCLASSIFIED
        return max(hits.items(), key=lambda kv: kv[1])[0]

    if subject == "英语":
        grade = str(item.get("grade", ""))
        m = re.search(r"U(\d)", grade)
        if m:
            return EN_UNIT_NAMES.get(int(m.group(1)), UNCLASSIFIED)
        return UNCLASSIFIED

    return UNCLASSIFIED
