"""SQLite 数据库管理模块，负责题库、系统配置与答题日志的持久化。"""

import json
import logging
import os
import sqlite3
import time
from contextlib import contextmanager
from typing import Any, Dict, Iterator, List, Optional

from .seed_data import DEFAULT_SETTINGS, SEED_QUESTIONS
from .classifier import classify_question, UNCLASSIFIED

logger = logging.getLogger(__name__)


def get_db_path() -> str:
    """获取数据库文件路径。

    Returns:
        数据库文件的绝对或相对路径。
    """
    return os.environ.get("MITV_DB_PATH", "/app/data/mitv.db")


@contextmanager
def get_connection() -> Iterator[sqlite3.Connection]:
    """创建并管理 SQLite 数据库连接上下文。

    Yields:
        配置好 Row 工厂的 sqlite3.Connection 实例。
    """
    db_path = get_db_path()
    parent_dir = os.path.dirname(os.path.abspath(db_path))
    if parent_dir:
        os.makedirs(parent_dir, exist_ok=True)
    conn = sqlite3.connect(db_path, timeout=10.0)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        logger.exception("数据库操作异常，已回滚")
        raise
    finally:
        conn.close()


def init_db() -> None:
    """初始化数据库表结构，并在空库时写入默认配置和种子题库。"""
    with get_connection() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS questions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                subject TEXT NOT NULL,
                grade TEXT NOT NULL,
                question TEXT NOT NULL,
                options_json TEXT NOT NULL,
                correct_index INTEGER NOT NULL,
                explanation TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS quiz_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL DEFAULT 'xiaomi-a55',
                subject TEXT NOT NULL DEFAULT '',
                question_id INTEGER NOT NULL DEFAULT 0,
                question_text TEXT NOT NULL DEFAULT '',
                is_correct INTEGER NOT NULL,
                pending_after INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """
        )

        for key, value in DEFAULT_SETTINGS.items():
            conn.execute(
                "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)",
                (key, value),
            )
        conn.execute(
            "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)",
            ("active_units", ""),
        )
        conn.execute(
            "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)",
            ("exclude_unclassified", "0"),
        )
        conn.execute(
            "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)",
            ("quiz_version", str(int(time.time()))),
        )
        conn.execute(
            "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)",
            ("remote_command_id", "0"),
        )
        conn.execute(
            "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)",
            ("remote_command_type", "none"),
        )
        conn.execute(
            "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)",
            ("remote_command_seconds", "3600"),
        )
        conn.execute(
            "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)",
            ("remote_command_at", "0"),
        )

        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS questions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                subject TEXT NOT NULL,
                grade TEXT NOT NULL,
                unit TEXT NOT NULL DEFAULT '',
                question TEXT NOT NULL,
                options_json TEXT NOT NULL,
                correct_index INTEGER NOT NULL,
                explanation TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL
            )
            """
        )
        # 旧库迁移：补 unit 列
        cols = [r["name"] for r in conn.execute("PRAGMA table_info(questions)").fetchall()]
        if "unit" not in cols:
            conn.execute("ALTER TABLE questions ADD COLUMN unit TEXT NOT NULL DEFAULT ''")
        backfill_units_in_conn(conn)

        row = conn.execute("SELECT COUNT(*) AS cnt FROM questions").fetchone()
        if row and row["cnt"] == 0:
            now_ts = int(time.time())
            use_classified = (
                os.environ.get("MITV_USE_CLASSIFIED", "").strip() == "1"
                and os.path.exists(
                    os.path.join(
                        os.path.dirname(os.path.abspath(__file__)),
                        "seed_classified.json",
                    )
                )
            )
            if use_classified:
                with open(
                    os.path.join(
                        os.path.dirname(os.path.abspath(__file__)),
                        "seed_classified.json",
                    ),
                    "r",
                    encoding="utf-8",
                ) as f:
                    combined = json.load(f)
                logger.info("按显式开关加载分类版题库 seed_classified.json")
            else:
                combined = list(SEED_QUESTIONS)
                for extra_name in (
                    "seed_grade4_pohw.json",
                    "seed_english_gz_term1.json",
                ):
                    extra_path = os.path.join(
                        os.path.dirname(os.path.abspath(__file__)),
                        extra_name,
                    )
                    if os.path.exists(extra_path):
                        with open(extra_path, "r", encoding="utf-8") as f:
                            extra_items = json.load(f)
                        if isinstance(extra_items, list):
                            combined.extend(extra_items)

            seen_questions = set()
            batch_rows = []
            for item in combined:
                q_text = str(item.get("question", "")).strip()
                if not q_text or q_text in seen_questions:
                    continue
                seen_questions.add(q_text)
                batch_rows.append(
                    (
                        item["subject"],
                        item.get("grade", "四年级上"),
                        classify_question(item),
                        q_text,
                        json.dumps(item["options"], ensure_ascii=False),
                        int(item["correct_index"]),
                        item.get("explanation", ""),
                        1,
                        now_ts,
                    )
                )
            conn.executemany(
                """
                INSERT INTO questions (
                    subject, grade, unit, question, options_json,
                    correct_index, explanation, enabled, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                batch_rows,
            )
            logger.info("已写入 %d 道初始四年级题库", len(batch_rows))


def backfill_units_in_conn(conn: sqlite3.Connection) -> int:
    """为空单元题目自动回填单元标签（不删除任何题，无法判断的标为“未分类”）。

    Args:
        conn: 当前活跃数据库连接。

    Returns:
        实际回填的题目数量。
    """
    rows = conn.execute(
        "SELECT id, subject, grade, question, explanation FROM questions WHERE unit IS NULL OR TRIM(unit) = ''"
    ).fetchall()
    if not rows:
        return 0
    updates = []
    for r in rows:
        unit = classify_question({
            "subject": r["subject"],
            "grade": r["grade"],
            "question": r["question"],
            "explanation": r["explanation"],
        })
        updates.append((unit, r["id"]))
    conn.executemany("UPDATE questions SET unit = ? WHERE id = ?", updates)
    bump_version(conn)
    logger.info("已为 %d 道旧题回填单元标签", len(updates))
    return len(updates)


def backfill_units() -> int:
    """对外入口：为现有在线题库回填单元标签。"""
    with get_connection() as conn:
        return backfill_units_in_conn(conn)


def bump_version(conn: sqlite3.Connection) -> int:
    """更新题库配置版本号并返回新版本号。

    Args:
        conn: 当前活跃的数据库连接。

    Returns:
        更新后的整数时间戳版本号。
    """
    new_ver = int(time.time())
    conn.execute(
        "INSERT OR REPLACE INTO settings (key, value) VALUES ('quiz_version', ?)",
        (str(new_ver),),
    )
    return new_ver


def row_to_question(row: sqlite3.Row) -> Dict[str, Any]:
    """将数据库行对象转换为题库字典。

    Args:
        row: SQLite 查询结果行。

    Returns:
        格式化后的题目字典。
    """
    return {
        "id": row["id"],
        "subject": row["subject"],
        "grade": row["grade"],
        "unit": row["unit"],
        "question": row["question"],
        "options": json.loads(row["options_json"]),
        "correct_index": row["correct_index"],
        "explanation": row["explanation"],
        "enabled": bool(row["enabled"]),
        "created_at": row["created_at"],
    }


def get_settings_dict(conn: sqlite3.Connection) -> Dict[str, Any]:
    """读取全部系统设置并返回类型化字典。

    Args:
        conn: 当前活跃的数据库连接。

    Returns:
        包含配置项的字典。
    """
    rows = conn.execute("SELECT key, value FROM settings").fetchall()
    raw = {row["key"]: row["value"] for row in rows}
    return {
        "watch_limit_seconds": int(raw.get("watch_limit_seconds", "600")),
        "wrong_penalty": int(raw.get("wrong_penalty", "1")),
        "max_pending_questions": int(raw.get("max_pending_questions", "5")),
        "parent_pin": str(raw.get("parent_pin", "1234")),
        "bypass_seconds": int(raw.get("bypass_seconds", "3600")),
        "quiz_version": int(raw.get("quiz_version", "1")),
        "active_units": str(raw.get("active_units", "")),
        "exclude_unclassified": int(raw.get("exclude_unclassified", "0")),
    }


def get_public_quiz_payload() -> Dict[str, Any]:
    """生成提供给电视端 APK 拉取的 JSON 负载。

    Returns:
        包含版本号、运行时配置及启用题库列表的字典。
    """
    with get_connection() as conn:
        cfg = get_settings_dict(conn)
        active_raw = str(cfg.get("active_units", "")).strip()
        active_tokens = [u.strip() for u in active_raw.split("|") if u.strip()]
        query = "SELECT * FROM questions WHERE enabled = 1"
        params: List[Any] = []
        if cfg.get("exclude_unclassified", 0):
            query += " AND unit NOT IN (?, ?)"
            params.extend([UNCLASSIFIED, ""])
        if active_tokens:
            clauses = []
            plain_units = []
            for token in active_tokens:
                if "::" in token:
                    subject, unit = token.split("::", 1)
                    if unit == UNCLASSIFIED:
                        clauses.append("(subject = ? AND (unit = ? OR unit = ?))")
                        params.extend([subject, UNCLASSIFIED, ""])
                    else:
                        clauses.append("(subject = ? AND unit = ?)")
                        params.extend([subject, unit])
                else:
                    plain_units.append(token)
            if plain_units:
                clauses.append("unit IN (" + ",".join("?" for _ in plain_units) + ")")
                params.extend(plain_units)
            if clauses:
                query += " AND (" + " OR ".join(clauses) + ")"
        query += " ORDER BY id ASC"
        rows = conn.execute(query, params).fetchall()
        if not rows and active_tokens:
            rows = conn.execute(
                "SELECT * FROM questions WHERE enabled = 1 ORDER BY id ASC"
            ).fetchall()
        questions = [
            {
                "id": r["id"],
                "subject": r["subject"],
                "grade": r["grade"],
                "unit": r["unit"] or UNCLASSIFIED,
                "question": r["question"],
                "options": json.loads(r["options_json"]),
                "correct_index": r["correct_index"],
                "explanation": r["explanation"],
            }
            for r in rows
        ]
        return {
            "version": cfg["quiz_version"],
            "config": {
                "watch_limit_seconds": cfg["watch_limit_seconds"],
                "wrong_penalty": cfg["wrong_penalty"],
                "max_pending_questions": cfg["max_pending_questions"],
                "parent_pin": cfg["parent_pin"],
                "bypass_seconds": cfg["bypass_seconds"],
            },
            "questions": questions,
        }


def list_questions(
    subject: Optional[str] = None,
    keyword: Optional[str] = None,
    unit: Optional[str] = None,
    page: int = 1,
    page_size: int = 30,
) -> Dict[str, Any]:
    """按学科、单元和关键字分页查询题目列表。

    Args:
        subject: 学科筛选条件，可选。
        keyword: 题干关键字，可选。
        unit: 单元筛选条件，可选。
        page: 页码（从 1 开始）。
        page_size: 每页条数，传 0 表示返回全部。

    Returns:
        包含总数、启用数、学科/单元分布与当前页题目列表的字典。
    """
    where_sql = " WHERE 1=1"
    params: List[Any] = []
    if subject:
        where_sql += " AND subject = ?"
        params.append(subject)
    if unit:
        where_sql += " AND unit = ?"
        params.append(unit)
    if keyword:
        where_sql += " AND (question LIKE ? OR explanation LIKE ?)"
        like_kw = f"%{keyword}%"
        params.extend([like_kw, like_kw])

    with get_connection() as conn:
        count_row = conn.execute(
            f"SELECT COUNT(*) AS total, SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END) AS enabled_total FROM questions{where_sql}",
            params,
        ).fetchone()
        total = count_row["total"] or 0
        enabled_total = count_row["enabled_total"] or 0

        stats_rows = conn.execute(
            "SELECT subject, COUNT(*) AS cnt FROM questions WHERE enabled = 1 GROUP BY subject"
        ).fetchall()
        subject_counts = {r["subject"]: r["cnt"] for r in stats_rows}

        grade_rows = conn.execute(
            "SELECT grade, COUNT(*) AS cnt FROM questions WHERE enabled = 1 GROUP BY grade ORDER BY grade"
        ).fetchall()
        grade_counts = {r["grade"]: r["cnt"] for r in grade_rows}

        unit_rows = conn.execute(
            "SELECT unit, COUNT(*) AS cnt FROM questions WHERE enabled = 1 AND unit != '' GROUP BY unit ORDER BY unit"
        ).fetchall()
        unit_counts = {r["unit"]: r["cnt"] for r in unit_rows}

        query = f"SELECT * FROM questions{where_sql} ORDER BY id DESC"
        query_params = list(params)
        if page_size > 0:
            offset = max(0, (page - 1) * page_size)
            query += " LIMIT ? OFFSET ?"
            query_params.extend([page_size, offset])

        rows = conn.execute(query, query_params).fetchall()
        return {
            "total": total,
            "enabled_total": enabled_total,
            "subject_counts": subject_counts,
            "unit_counts": unit_counts,
            "grade_counts": grade_counts,
            "page": page,
            "page_size": page_size,
            "questions": [row_to_question(r) for r in rows],
        }


def create_question(data: Dict[str, Any]) -> Dict[str, Any]:
    """新增一道题目。

    Args:
        data: 包含题干、选项、答案等字段的字典。

    Returns:
        新建成功的题目字典。
    """
    now_ts = int(time.time())
    with get_connection() as conn:
        cursor = conn.execute(
            """
            INSERT INTO questions (
                subject, grade, unit, question, options_json,
                correct_index, explanation, enabled, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                data["subject"],
                data["grade"],
                data.get("unit", ""),
                data["question"],
                json.dumps(data["options"], ensure_ascii=False),
                int(data["correct_index"]),
                data.get("explanation", ""),
                1 if data.get("enabled", True) else 0,
                now_ts,
            ),
        )
        bump_version(conn)
        row = conn.execute(
            "SELECT * FROM questions WHERE id = ?",
            (cursor.lastrowid,),
        ).fetchone()
        return row_to_question(row)


def update_question(question_id: int, data: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """更新指定 ID 的题目。

    Args:
        question_id: 题目主键 ID。
        data: 更新字段字典。

    Returns:
        更新后的题目字典，若不存在则返回 None。
    """
    with get_connection() as conn:
        existing = conn.execute(
            "SELECT * FROM questions WHERE id = ?",
            (question_id,),
        ).fetchone()
        if not existing:
            return None
        conn.execute(
            """
            UPDATE questions
            SET subject = ?, grade = ?, unit = ?, question = ?, options_json = ?,
                correct_index = ?, explanation = ?, enabled = ?
            WHERE id = ?
            """,
            (
                data["subject"],
                data["grade"],
                data.get("unit", ""),
                data["question"],
                json.dumps(data["options"], ensure_ascii=False),
                int(data["correct_index"]),
                data.get("explanation", ""),
                1 if data.get("enabled", True) else 0,
                question_id,
            ),
        )
        bump_version(conn)
        row = conn.execute(
            "SELECT * FROM questions WHERE id = ?",
            (question_id,),
        ).fetchone()
        return row_to_question(row)


def delete_question(question_id: int) -> bool:
    """删除指定 ID 的题目。

    Args:
        question_id: 题目主键 ID。

    Returns:
        删除成功返回 True，未找到返回 False。
    """
    with get_connection() as conn:
        cursor = conn.execute("DELETE FROM questions WHERE id = ?", (question_id,))
        if cursor.rowcount > 0:
            bump_version(conn)
            return True
        return False


def bulk_import_questions(
    items: List[Dict[str, Any]],
    replace_all: bool = False,
) -> int:
    """批量导入题目。

    Args:
        items: 题目字典列表。
        replace_all: 为 True 时先清空现有题库再导入，为 False 时追加。

    Returns:
        成功导入的题目数量。
    """
    now_ts = int(time.time())
    with get_connection() as conn:
        if replace_all:
            conn.execute("DELETE FROM questions")
        count = 0
        for item in items:
            conn.execute(
                """
                INSERT INTO questions (
                    subject, grade, unit, question, options_json,
                    correct_index, explanation, enabled, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    item["subject"],
                    item.get("grade", "四年级上"),
                    item.get("unit", ""),
                    item["question"],
                    json.dumps(item["options"], ensure_ascii=False),
                    int(item["correct_index"]),
                    item.get("explanation", ""),
                    1 if item.get("enabled", True) else 0,
                    now_ts,
                ),
            )
            count += 1
        bump_version(conn)
        return count


def update_settings(updates: Dict[str, Any]) -> Dict[str, Any]:
    """更新系统运行参数。

    Args:
        updates: 键为参数名、值为参数值的字典。

    Returns:
        更新后的完整系统参数字典。
    """
    allowed_keys = set(DEFAULT_SETTINGS.keys()) | {
        "active_units",
        "exclude_unclassified",
    }
    with get_connection() as conn:
        for key, value in updates.items():
            if key in allowed_keys:
                conn.execute(
                    "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
                    (key, str(value)),
                )
        bump_version(conn)
        return get_settings_dict(conn)


def list_units_by_subject() -> Dict[str, List[Dict[str, Any]]]:
    """按学科分组统计单元（供题库配置页按科目渲染）。

    Returns:
        形如 {学科: [{"unit": 单元名, "count": 题数}]} 的字典。
    """
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT subject, unit, COUNT(*) AS cnt FROM questions
            WHERE enabled = 1
            GROUP BY subject, unit
            ORDER BY subject, unit
            """
        ).fetchall()
        result: Dict[str, List[Dict[str, Any]]] = {}
        for r in rows:
            subject = r["subject"]
            unit = r["unit"] or UNCLASSIFIED
            result.setdefault(subject, []).append({"unit": unit, "count": r["cnt"]})
        return result


def list_seed_files() -> List[Dict[str, Any]]:
    """列出题库配置页可管理的独立 JSON 题库文件。

    Returns:
        文件名、题数、说明与是否已启用的清单。
    """
    import glob

    base_dir = os.path.dirname(os.path.abspath(__file__))
    file_notes = {
        "seed_classified.json": "按单元清洗后的现有题库（408 题，可一键载入替换在线库）",
        "seed_english_gz_term1.json": "教科版四年级上册英语真卷提取（215 题）",
        "seed_grade4_pohw.json": "数学/语文上册清洗题（336 题）",
        "seed_math_workbook_draft.json": "学霸数学卷 OCR 草稿（全部停用，需人工校对）",
        "seed_math_workbook_verified.json": "学霸数学卷答案已对齐候选（37 题）",
        "seed_math_doubao_term1u1.json": "第1单元 大数的认识练习题（28 题：7 题资料解析明确、21 题按题干验算需抽查）",
        "seed_math_dianzhong_tests.json": "数学测试卷选择题（8 题，OCR 提取建议抽查）",
        "seed_chinese_5xing_reading.json": "语文阅读理解专项（6 题，依据文章解读转换，带原文片段）",
        "seed_english_textbook_u1u8.json": "英语新教材提纲补充题（29 题，严格按 U1-U8 提纲生成：情景应答/词汇/语法）",
        "english_outline_u1u8.json": "新教材 U1-U8 提纲（主题/核心词汇/句型），生成英语题的唯一基准",
    }
    out = []
    for path in sorted(glob.glob(os.path.join(base_dir, "seed_*.json"))):
        name = os.path.basename(path)
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            count = len(data) if isinstance(data, list) else 0
        except Exception:
            count = 0
        out.append({
            "name": name,
            "count": count,
            "note": file_notes.get(name, "独立题库文件"),
            "size_kb": round(os.path.getsize(path) / 1024, 1),
        })
    return out


def load_seed_file(name: str, replace_all: bool) -> int:
    """将独立题库文件导入在线库（追加或替换），导入前自动补单元标签。

    Args:
        name: seed_*.json 文件名（禁止路径穿越）。
        replace_all: 是否先清空现有题库。

    Returns:
        导入题数。
    """
    if not name.startswith("seed_") or not name.endswith(".json") or "/" in name:
        raise ValueError("非法题库文件名")
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), name)
    if not os.path.exists(path):
        raise FileNotFoundError(name)
    with open(path, "r", encoding="utf-8") as f:
        items = json.load(f)
    if not isinstance(items, list):
        raise ValueError("题库文件必须是 JSON 数组")
    normalized = []
    for item in items:
        q = dict(item)
        q["unit"] = classify_question(q) or UNCLASSIFIED
        normalized.append(q)
    return bulk_import_questions(normalized, replace_all=replace_all)


def count_questions() -> int:
    """统计全部题目总数。

    Returns:
        题库总题数。
    """
    with get_connection() as conn:
        row = conn.execute("SELECT COUNT(*) AS cnt FROM questions").fetchone()
        return row["cnt"] if row else 0


def add_quiz_log(data: Dict[str, Any]) -> None:
    """记录一条电视端上报的答题结果。

    Args:
        data: 包含设备 ID、题目信息和作答结果的字典。
    """
    now_ts = int(time.time())
    with get_connection() as conn:
        conn.execute(
            """
            INSERT INTO quiz_logs (
                device_id, subject, question_id, question_text,
                is_correct, pending_after, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                data.get("device_id", "xiaomi-a55"),
                data.get("subject", ""),
                int(data.get("question_id", 0)),
                data.get("question_text", ""),
                1 if data.get("is_correct") else 0,
                int(data.get("pending_after", 0)),
                now_ts,
            ),
        )


def list_quiz_logs(limit: int = 50) -> Dict[str, Any]:
    """查询最近答题记录及统计汇总。

    Args:
        limit: 返回的最大日志条数。

    Returns:
        包含统计摘要与记录列表的字典。
    """
    with get_connection() as conn:
        stats_row = conn.execute(
            """
            SELECT
                COUNT(*) AS total,
                SUM(CASE WHEN is_correct = 1 THEN 1 ELSE 0 END) AS correct_count
            FROM quiz_logs
            """
        ).fetchone()
        total = stats_row["total"] or 0
        correct_count = stats_row["correct_count"] or 0
        rows = conn.execute(
            "SELECT * FROM quiz_logs ORDER BY id DESC LIMIT ?",
            (limit,),
        ).fetchall()
        logs = [
            {
                "id": r["id"],
                "device_id": r["device_id"],
                "subject": r["subject"],
                "question_id": r["question_id"],
                "question_text": r["question_text"],
                "is_correct": bool(r["is_correct"]),
                "pending_after": r["pending_after"],
                "created_at": r["created_at"],
            }
            for r in rows
        ]
        return {
            "total": total,
            "correct_count": correct_count,
            "wrong_count": total - correct_count,
            "logs": logs,
        }


def issue_remote_command(command_type: str, seconds: int = 3600) -> Dict[str, Any]:
    """下发一条家长远程控制指令（放行、提前结束放行或立即锁屏）。

    Args:
        command_type: 指令类型，支持 bypass / end_bypass / lock_now。
        seconds: 放行时长秒数，仅 bypass 指令有效。

    Returns:
        最新指令状态字典。
    """
    now_ts = int(time.time())
    with get_connection() as conn:
        row = conn.execute(
            "SELECT value FROM settings WHERE key = 'remote_command_id'"
        ).fetchone()
        current_id = int(row["value"]) if row else 0
        next_id = max(now_ts, current_id + 1)
        conn.execute(
            "INSERT OR REPLACE INTO settings (key, value) VALUES ('remote_command_id', ?)",
            (str(next_id),),
        )
        conn.execute(
            "INSERT OR REPLACE INTO settings (key, value) VALUES ('remote_command_type', ?)",
            (command_type,),
        )
        conn.execute(
            "INSERT OR REPLACE INTO settings (key, value) VALUES ('remote_command_seconds', ?)",
            (str(max(60, int(seconds))),),
        )
        conn.execute(
            "INSERT OR REPLACE INTO settings (key, value) VALUES ('remote_command_at', ?)",
            (str(now_ts),),
        )
        return {
            "command_id": next_id,
            "command": command_type,
            "seconds": max(60, int(seconds)),
            "issued_at": now_ts,
        }


def get_remote_command() -> Dict[str, Any]:
    """读取当前最新远程控制指令状态。

    Returns:
        包含指令 ID、类型、时长和下发时间戳的字典。
    """
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT key, value FROM settings
            WHERE key IN (
                'remote_command_id',
                'remote_command_type',
                'remote_command_seconds',
                'remote_command_at'
            )
            """
        ).fetchall()
        raw = {r["key"]: r["value"] for r in rows}
        return {
            "command_id": int(raw.get("remote_command_id", "0")),
            "command": raw.get("remote_command_type", "none"),
            "seconds": int(raw.get("remote_command_seconds", "3600")),
            "issued_at": int(raw.get("remote_command_at", "0")),
        }
