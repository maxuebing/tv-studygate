"""小米电视防沉迷答题系统 Web 服务入口。"""

import asyncio
import json
import logging
import os
import re
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator, Dict, List, Optional

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field, field_validator

from . import database

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    """应用生命周期管理，启动时初始化数据库。"""
    database.init_db()
    logger.info("题库管理服务初始化完成")
    yield


app = FastAPI(
    title="小米电视四年级防沉迷题库系统",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class QuestionPayload(BaseModel):
    """单道题目创建或更新请求模型。"""

    subject: str = Field(..., min_length=1, max_length=20)
    grade: str = Field(default="四年级上", min_length=1, max_length=30)
    unit: str = Field(default="", max_length=30)
    question: str = Field(..., min_length=2, max_length=500)
    options: List[str] = Field(..., min_length=4, max_length=4)
    correct_index: int = Field(..., ge=0, le=3)
    explanation: str = Field(default="", max_length=500)
    enabled: bool = Field(default=True)

    @field_validator("options")
    @classmethod
    def validate_options(cls, value: List[str]) -> List[str]:
        """校验四个选项均非空。"""
        cleaned = [opt.strip() for opt in value]
        if len(cleaned) != 4 or any(not opt for opt in cleaned):
            raise ValueError("必须提供 4 个非空选项")
        return cleaned


class BulkImportPayload(BaseModel):
    """批量导入题库请求模型。"""

    replace_all: bool = Field(default=False)
    questions: List[QuestionPayload] = Field(..., min_length=1)


class SettingsPayload(BaseModel):
    """系统配置更新请求模型。"""

    watch_limit_seconds: Optional[int] = Field(default=None, ge=30, le=7200)
    wrong_penalty: Optional[int] = Field(default=None, ge=0, le=3)
    max_pending_questions: Optional[int] = Field(default=None, ge=1, le=20)
    parent_pin: Optional[str] = Field(default=None, pattern=r"^\d{4}$")
    bypass_seconds: Optional[int] = Field(default=None, ge=60, le=14400)
    active_units: Optional[str] = Field(default=None, max_length=1000)


class QuizReportPayload(BaseModel):
    """电视端上报单次答题日志请求模型。"""

    device_id: str = Field(default="xiaomi-a55", max_length=64)
    subject: str = Field(default="", max_length=20)
    question_id: int = Field(default=0)
    question_text: str = Field(default="", max_length=500)
    is_correct: bool
    pending_after: int = Field(default=0, ge=0)


class LoadSeedPayload(BaseModel):
    """题库配置页导入独立题库文件请求模型。"""

    name: str = Field(..., min_length=5, max_length=100)
    replace_all: bool = Field(default=False)


class RemoteControlPayload(BaseModel):
    """家长手机端远程控制指令请求模型。"""

    command: str = Field(..., pattern=r"^(bypass|end_bypass|lock_now)$")
    seconds: int = Field(default=3600, ge=60, le=14400)


class ActiveUnitsPayload(BaseModel):
    """出题单元勾选请求模型。"""

    units: List[str] = Field(default_factory=list, max_length=50)
    exclude_unclassified: bool = Field(default=False)


class GenerateQuizPayload(BaseModel):
    """AI 生成题目请求模型。"""

    topic: str = Field(..., min_length=2, max_length=200)
    count: int = Field(default=10, ge=5, le=30)
    subject: str = Field(default="数学", max_length=20)

    @field_validator("topic")
    @classmethod
    def validate_topic(cls, value: str) -> str:
        """拦截提示词注入风险的关键字。"""
        forbidden = ["ignore", "system prompt", "ignore previous", "api", "key"]
        lowered = value.lower()
        if any(k in lowered for k in forbidden):
            raise ValueError("题目描述中包含不允许的关键字")
        return value.strip()


class GeneratedQuestionPayload(BaseModel):
    """AI 生成或家长手录的单题入库模型（与 QuestionPayload 一致）。"""

    subject: str = Field(..., min_length=1, max_length=20)
    grade: str = Field(default="四年级上", min_length=1, max_length=30)
    question: str = Field(..., min_length=2, max_length=500)
    options: List[str] = Field(..., min_length=4, max_length=4)
    correct_index: int = Field(..., ge=0, le=3)
    explanation: str = Field(default="", max_length=500)
    enabled: bool = Field(default=True)


@app.get("/")
async def index_page() -> FileResponse:
    """返回家长题库管理单页应用。"""
    return FileResponse(os.path.join(STATIC_DIR, "index.html"))


@app.get("/print.html")
async def print_page() -> FileResponse:
    """返回按年级/学科/单元分类的打印视图。"""
    return FileResponse(os.path.join(STATIC_DIR, "print.html"))


@app.get("/health")
async def health_check() -> Dict[str, str]:
    """健康检查接口。"""
    return {"status": "ok"}


@app.get("/api/quiz.json")
async def get_quiz_json() -> JSONResponse:
    """提供给小米电视 APK 拉取的统一题库与配置 JSON 接口。"""
    payload = database.get_public_quiz_payload()
    return JSONResponse(content=payload)


@app.post("/api/report")
async def report_quiz_result(body: QuizReportPayload) -> Dict[str, str]:
    """接收电视端上报的答题结果并落库。"""
    database.add_quiz_log(body.model_dump())
    return {"status": "recorded"}


@app.get("/api/remote/command")
async def get_remote_command() -> Dict[str, Any]:
    """供电视端轻量轮询的远程放行/锁屏指令接口。"""
    return database.get_remote_command()


@app.post("/api/admin/remote")
async def admin_issue_remote_command(body: RemoteControlPayload) -> Dict[str, Any]:
    """家长手机网页端下发远程控制指令（防偷窥解锁/放行/恢复守护）。"""
    return database.issue_remote_command(body.command, body.seconds)


@app.post("/api/admin/ai-generate")
async def admin_ai_generate_quiz(body: GenerateQuizPayload) -> Dict[str, Any]:
    """调用兼容 OpenAI 协议的大模型接口批量生成四选一题目并入库。

    通过环境变量配置 AI_API_BASE（如 https://api.deepseek.com/v1）、
    AI_API_KEY（sk-开头密钥）与 AI_MODEL（模型名，默认 deepseek-chat）。
    """
    api_base = os.environ.get("AI_API_BASE", "").strip()
    api_key = os.environ.get("AI_API_KEY", "").strip()
    model = os.environ.get("AI_MODEL", "deepseek-chat").strip()
    if not api_base or not api_key:
        raise HTTPException(
            status_code=400,
            detail="尚未配置 AI 出题服务，请在 docker-compose.yml 中设置 AI_API_BASE 与 AI_API_KEY 环境变量",
        )

    prompt = (
        f"你是小学四年级题库助手。请围绕主题「{body.topic}」生成 {body.count} 道"
        f"四年级上册{body.subject}四选一选择题，严格要求："
        "1. 题目必须符合人教版/广州教科版四年级上册教学范围，不得超纲；"
        "2. 每题恰好 4 个选项，正确答案唯一且位置随机分布；"
        "3. 严格按以下 JSON 格式输出，不要输出任何其他文字："
        '{"questions":[{"subject":"学科","grade":"四年级上","question":"题干",'
        '"options":["A选项","B选项","C选项","D选项"],"correct_index":0,'
        '"explanation":"一句话解析"}]}'
    )

    request_body = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.7,
        "max_tokens": 4000,
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    def call_llm() -> str:
        """同步调用大模型接口并返回原始文本。"""
        import urllib.request
        import urllib.error

        req = urllib.request.Request(
            f"{api_base.rstrip('/')}/chat/completions",
            data=json.dumps(request_body).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data["choices"][0]["message"]["content"]

    import asyncio

    try:
        raw_text = await asyncio.wait_for(asyncio.to_thread(call_llm), timeout=125)
    except asyncio.TimeoutError:
        raise HTTPException(status_code=504, detail="AI 接口超时，请稍后重试")
    except Exception as exc:
        logger.exception("AI 出题接口调用失败")
        raise HTTPException(status_code=502, detail=f"AI 接口调用失败：{exc}")

    try:
        match = re.search(r"\{.*\}", raw_text, re.S)
        if not match:
            raise ValueError("未找到 JSON 内容")
        parsed = json.loads(match.group(0))
        items = parsed.get("questions", [])
        if not items:
            raise ValueError("AI 未返回任何题目")
        questions = database.bulk_import_questions(items, replace_all=False)
        return {"generated": questions, "total": database.count_questions()}
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("AI 返回内容解析失败：%s", raw_text[:500])
        raise HTTPException(status_code=502, detail=f"AI 返回内容无法解析：{exc}")


@app.get("/api/admin/questions")
async def admin_list_questions(
    subject: Optional[str] = Query(default=None),
    keyword: Optional[str] = Query(default=None),
    unit: Optional[str] = Query(default=None),
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=30, ge=1, le=200),
) -> Dict[str, Any]:
    """分页查询题库列表（支持学科/单元筛选）。"""
    return database.list_questions(
        subject=subject,
        keyword=keyword,
        unit=unit,
        page=page,
        page_size=page_size,
    )


@app.post("/api/admin/questions")
async def admin_create_question(body: QuestionPayload) -> Dict[str, Any]:
    """新增单道题目。"""
    created = database.create_question(body.model_dump())
    return {"question": created}


@app.put("/api/admin/questions/{question_id}")
async def admin_update_question(
    question_id: int,
    body: QuestionPayload,
) -> Dict[str, Any]:
    """更新指定 ID 题目。"""
    updated = database.update_question(question_id, body.model_dump())
    if not updated:
        raise HTTPException(status_code=404, detail="题目不存在")
    return {"question": updated}


@app.delete("/api/admin/questions/{question_id}")
async def admin_delete_question(question_id: int) -> Dict[str, bool]:
    """删除指定 ID 题目。"""
    ok = database.delete_question(question_id)
    if not ok:
        raise HTTPException(status_code=404, detail="题目不存在")
    return {"deleted": True}


@app.post("/api/admin/import")
async def admin_import_questions(body: BulkImportPayload) -> Dict[str, Any]:
    """批量导入 JSON 题目列表。"""
    items = [q.model_dump() for q in body.questions]
    count = database.bulk_import_questions(items, replace_all=body.replace_all)
    return {"imported": count}


@app.get("/api/admin/export")
async def admin_export_questions() -> JSONResponse:
    """导出全部题库为标准 JSON 文件。"""
    items = database.list_questions(page=1, page_size=0)["questions"]
    export_items = [
        {
            "subject": q["subject"],
            "grade": q["grade"],
            "unit": q["unit"],
            "question": q["question"],
            "options": q["options"],
            "correct_index": q["correct_index"],
            "explanation": q["explanation"],
            "enabled": q["enabled"],
        }
        for q in items
    ]
    return JSONResponse(
        content={"questions": export_items},
        headers={"Content-Disposition": "attachment; filename=mitv-quiz-backup.json"},
    )


@app.get("/api/admin/settings")
async def admin_get_settings() -> Dict[str, Any]:
    """读取当前系统运行参数。"""
    with database.get_connection() as conn:
        return database.get_settings_dict(conn)


@app.get("/api/admin/library")
async def admin_library_status() -> Dict[str, Any]:
    """题库配置页：返回单元分布、当前出题单元与独立题库文件清单。"""
    info = database.list_questions(page=1, page_size=1)
    with database.get_connection() as conn:
        cfg = database.get_settings_dict(conn)
    active_raw = str(cfg.get("active_units", "")).strip()
    active_units = [u for u in active_raw.split("|") if u]
    return {
        "total": info["total"],
        "enabled_total": info["enabled_total"],
        "unit_counts": info["unit_counts"],
        "units_by_subject": database.list_units_by_subject(),
        "grade_counts": info["grade_counts"],
        "active_units": active_units,
        "exclude_unclassified": bool(cfg.get("exclude_unclassified", 0)),
        "seed_files": database.list_seed_files(),
    }


@app.post("/api/admin/units")
async def admin_save_active_units(body: ActiveUnitsPayload) -> Dict[str, Any]:
    """保存当前允许电视出题的单元列表（空列表表示全部单元）。"""
    units = [u.strip() for u in body.units if u.strip()]
    result = database.update_settings({
        "active_units": "|".join(units),
        "exclude_unclassified": 1 if body.exclude_unclassified else 0,
    })
    return {
        "active_units": units,
        "exclude_unclassified": bool(body.exclude_unclassified),
        "quiz_version": result["quiz_version"],
    }


@app.post("/api/admin/units/backfill")
async def admin_backfill_units() -> Dict[str, Any]:
    """手动触发一次：为在线库中尚无单元标签的题目回填分类。"""
    count = database.backfill_units()
    return {"backfilled": count}


@app.post("/api/admin/library/load")
async def admin_load_seed_file(body: LoadSeedPayload) -> Dict[str, Any]:
    """将独立题库文件导入在线库（不勾选 replace_all 时为追加）。"""
    try:
        count = database.load_seed_file(body.name, body.replace_all)
    except (ValueError, FileNotFoundError) as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return {"loaded": count, "file": body.name, "replace_all": body.replace_all}


@app.put("/api/admin/settings")
async def admin_update_settings(body: SettingsPayload) -> Dict[str, Any]:
    """更新系统运行参数。"""
    updates = {k: v for k, v in body.model_dump().items() if v is not None}
    if not updates:
        raise HTTPException(status_code=400, detail="没有提供需要更新的参数")
    return database.update_settings(updates)


@app.get("/api/admin/logs")
async def admin_get_logs(limit: int = Query(default=50, ge=1, le=200)) -> Dict[str, Any]:
    """查询孩子近期答题记录与正确率统计。"""
    return database.list_quiz_logs(limit=limit)
