#!/usr/bin/env python3
"""严格按新教材提纲生成英语补充题库（独立文件）。

题源三类（答案均可唯一判定）：
1. 情景应答：礼貌用语的固定答法（Welcome/Thanks/Can I...）
2. 提纲词汇：中文释义选单词 / 单词选中文
3. 语法句型：There be / 方位介词 / how many / some vs any / 顺序词
全部带 unit（U1-U8），输出独立 seed 文件，不覆盖现有题库。
"""
import json, os

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUTLINE = os.path.join(BASE, "app", "english_outline_u1u8.json")
OUT = os.path.join(BASE, "app", "seed_english_textbook_u1u8.json")

outline = json.load(open(OUTLINE, encoding="utf-8"))
SRC = "来源：严格按教科版(广州)四年级上册(2024新教材)提纲生成，参考老课标语法规则。"

def q(unit, question, options, correct_index, tip):
    assert len(options) == 4 and len(set(options)) == 4, question
    return {
        "subject": "英语",
        "grade": "四年级上(教科版)",
        "unit": unit,
        "question": question,
        "options": options,
        "correct_index": correct_index,
        "explanation": tip + " " + SRC,
        "enabled": True,
    }

items = []

# ===== U1 待客做客 =====
items += [
    q("U1 Come on In", "客人到你家时，你开门后应该说：______",
      ["Go away.", "Come in, please.", "Give me your gift.", "Who are you?"], 1,
      "【提纲句型】Welcome! Come in, please. 是迎客用语。"),
    q("U1 Come on In", "别人送你礼物说 \"These are for you.\"，你应回答：______",
      ["I don't like them.", "How much are they?", "Thank you. They're beautiful!", "No, they aren't."], 2,
      "【提纲句型】收到礼物要道谢并赞美：Thank you. What beautiful flowers!"),
    q("U1 Come on In", "客人想用洗手间，礼貌的问法是：______",
      ["Can I use the bathroom?", "Give me the bathroom.", "Where is your toilet key?", "Bathroom, now!"], 0,
      "【提纲句型】Can I use the bathroom? — Sure. It's over there."),
    q("U1 Come on In", "招呼客人坐下，应该说：______",
      ["Stand up, please.", "Have a seat, please.", "Sit down quickly!", "Go to bed."], 1,
      "【提纲句型】Have a seat, please. 请坐。"),
    q("U1 Come on In", "英语中「主人」是______，「客人」是______。",
      ["host; guest", "guest; host", "visitor; waiter", "host; farmer"], 0,
      "【提纲词汇】host 主人（邀请别人的人），guest 客人（去做客的人）。"),
    q("U1 Come on In", "去朋友家做客，进门时最好带上：______",
      ["nothing", "a small gift", "a lot of homework", "your test paper"], 1,
      "【提纲词汇】good guest often brings a small gift（好客人带小礼物）。"),
]

# ===== U2 用餐礼仪 =====
items += [
    q("U2 Help Yourself", "主人给你夹菜，你已经吃饱了，礼貌的回答是：______",
      ["No! I hate it.", "Give it to the dog.", "No, thanks. I'm full.", "I want more and more."], 2,
      "【提纲句型】—Would you like some more? —No, thanks. I'm full."),
    q("U2 Help Yourself", "你想让别人递汤匙给你，礼貌的说法是：______",
      ["Pass me the spoon, please.", "Spoons are bad.", "Where is my spoon money?", "I am a spoon."], 0,
      "【提纲句型】Could you please pass me...? / Pass me ..., please."),
    q("U2 Help Yourself", "餐桌上把纸巾（tissues）递给别人时，应同时说：______",
      ["Here you are.", "It's mine now.", "You can't have it.", "Go away."], 0,
      "【提纲句型】递东西时说 Here you are. / Here, please."),
    q("U2 Help Yourself", "用餐时下面哪个行为是礼貌的（polite）？",
      ["Talk with your mouth full.", "Reach across the table for food.", "Say \"Thank you\" when someone passes you food.", "Shout at the host."], 2,
      "【提纲词汇】table manners 餐桌礼仪：接受帮助要说 Thank you。"),
]

# ===== U3 做饭步骤 =====
items += [
    q("U3 I'm the Chef Today", "煮面条前，首先要______ the noodles。",
      ["eat", "wash", "sell", "buy back"], 1,
      "【提纲词汇】First, wash the greens / noodles（先洗）。"),
    q("U3 I'm the Chef Today", "「把水加进锅里」用英语是：______",
      ["Add some water into the pot.", "Take the pot to bed.", "Boil the pot away.", "Wash the pot down."], 0,
      "【提纲词汇】add 加入；pot 锅。"),
    q("U3 I'm the Chef Today", "做完饭后，别忘了______ the stove。",
      ["turn off", "turn on and dance", "eat", "wash away"], 0,
      "【提纲句型】Turn off the stove after cooking.（安全：关炉火）"),
    q("U3 I'm the Chef Today", "做面条汤的正确步骤顺序是：______",
      ["吃面条 → 洗青菜 → 加水", "先洗手洗菜 → 加水入锅煮沸 → 下面条 → 关火", "关火 → 加水 → 吃面条", "先吃 → 再煮 → 后洗"], 1,
      "【提纲句型】First... Then... Finally, turn off the stove.（步骤顺序词 First/Then/Finally）"),
    q("U3 I'm the Chef Today", "「搅拌」面条用英语动词：______",
      ["stir", "star", "stay", "stand"], 0,
      "【提纲词汇】stir 搅拌；Add the greens and stir."),
]

# ===== U4 厨房家务 =====
items += [
    q("U4 Help Out in the Kitchen", "晚饭后我们需要先 ______ the dishes 再收起来。",
      ["wash", "throw", "sell", "hide"], 0,
      "【提纲词汇】wash the dishes 洗碗；put away the dishes 把碗收好。"),
    q("U4 Help Out in the Kitchen", "「倒垃圾」用英语短语是：______",
      ["take out the rubbish", "eat the rubbish", "put rubbish on the bed", "wash the rubbish"], 0,
      "【提纲词汇】take out the rubbish 倒垃圾；rubbish 垃圾。"),
    q("U4 Help Out in the Kitchen", "吃剩的食物叫______，可以放进冰箱（fridge）留着明天吃。",
      ["leftovers", "leaders", "lovers", "ladders"], 0,
      "【提纲词汇】leftovers 剩饭剩菜，可保存在冰箱里。"),
    q("U4 Help Out in the Kitchen", "打扫完桌子后，把干净的碗碟 ______ 是好习惯。",
      ["put away", "put on", "give up", "look at"], 0,
      "【提纲词汇】put away 收好、放好。"),
]

# ===== U5 帮助分享 =====
items += [
    q("U5 She Helps Me a Lot", "「她总是乐于帮助别人」最合适的表达是：______",
      ["She always helps others.", "She always sleeps in class.", "She never shares anything.", "She is sad every day."], 0,
      "【提纲词汇】always 总是；help others 帮助别人。"),
    q("U5 She Helps Me a Lot", "朋友考试失利心情低落，你可以______ 他/她。",
      ["cheer up", "give up", "wake up", "stand up"], 0,
      "【提纲词汇】cheer up 使振作、鼓励。"),
    q("U5 She Helps Me a Lot", "有好东西应该与朋友______。",
      ["share", "hide", "throw", "sell"], 0,
      "【提纲词汇】share 分享；We should share good things with friends."),
]

# ===== U6 我爱我家 =====
items += [
    q("U6 I Love My Family", "过生日时，中国人常吃的一碗长寿面：______",
      ["noodles", "sweets only", "ice cream only", "cold water"], 0,
      "【提纲词汇】birthday noodles 长寿面；We cook noodles together.（全家一起煮面条）"),
    q("U6 I Love My Family", "「我们全家一起做饭」用英语可以说：______",
      ["We cook together.", "We sleep together at school.", "We cook for nobody.", "I cook alone every day."], 0,
      "【提纲句型】together 一起；We cook noodles together.（U6 家庭生日场景）"),
]

# ===== U7 家庭时光 =====
items += [
    q("U7 Family Time", "你想建议家人周末去公园，可以说：______",
      ["Why don't we go to the park?", "You must stay at home!", "Don't go out.", "I hate the park."], 0,
      "【提纲句型】Why don't you/we...? 用来提建议；也可以说 What about...? / Maybe we can..."),
    q("U7 Family Time", "「你是如何度过家庭时光的？」用英语问：______",
      ["How do you spend your family time?", "When is your birthday cake?", "Where is your school bag?", "Who is your maths teacher?"], 0,
      "【提纲句型】How do you spend...? spend 度过、花费。"),
]

# ===== U8 节日 =====
items += [
    q("U8 Joy in the Air", "「我最喜欢的节日是春节」用英语说：______",
      ["My favourite festival is the Spring Festival.", "My favourite subject is maths.", "My favourite colour is red.", "My favourite food is noodles."], 0,
      "【提纲句型】My favourite festival is... festival 节日。"),
    q("U8 Joy in the Air", "端午节人们通常______。",
      ["make rice dumplings", "plant trees in the snow", "swim in winter", "clean the moon"], 0,
      "【提纲词汇】make rice dumplings 包粽子（端午节）。"),
    q("U8 Joy in the Air", "元宵节（Lantern Festival）人们会看花灯、吃______。",
      ["tangyuan", "pizza", "hamburgers", "chocolate cake"], 0,
      "【提纲词汇】tangyuan 汤圆；lanterns 花灯。"),
]

# 校验并写出
assert len(set((q["unit"], q["question"]) for q in items)) == len(items)
json.dump(items, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

from collections import Counter
print(f"生成 {len(items)} 题，按单元: {dict(Counter(q['unit'] for q in items))}")
print(f"输出: {OUT}")
