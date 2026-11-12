"""Renders email subject, HTML body and plain-text body for a notification."""
from __future__ import annotations

import html
import json
import re
from datetime import datetime
from typing import Any

from jinja2 import Environment, PackageLoader, StrictUndefined, select_autoescape

from .contracts import NotificationType


def money(amount_cents: int, currency: str = "SGD") -> str:
    return f"{currency.upper()} {amount_cents / 100:,.2f}"


def event_date(value: str) -> str:
    """'2026-11-14T20:00:00+08:00' -> 'Sat, 14 Nov 2026, 8:00 PM'. Unparseable dates pass through."""
    try:
        dt = datetime.fromisoformat(value)
    except (TypeError, ValueError):
        return value
    if dt.hour == 0 and dt.minute == 0 and "T" not in value:
        return dt.strftime("%a, %d %b %Y")
    return dt.strftime("%a, %d %b %Y, %I:%M %p").replace(", 0", ", ")


def pretty(value: Any) -> str:
    if isinstance(value, str):
        return value
    return json.dumps(value, indent=2, ensure_ascii=False, default=str)


# HTML bodies: autoescape ON, so an event titled "<script>" can't inject markup.
_html_env = Environment(
    loader=PackageLoader("app", "email_templates"),
    autoescape=select_autoescape(["html"]),
    undefined=StrictUndefined,
    trim_blocks=True,
    lstrip_blocks=True,
)
# Subjects are plain text, so no HTML escaping.
_text_env = Environment(autoescape=False, undefined=StrictUndefined)

for _env in (_html_env, _text_env):
    _env.filters.update(money=money, event_date=event_date, pretty=pretty)


def html_to_text(markup: str) -> str:
    """Crude but dependable plain-text version for email clients that want one."""
    text = re.sub(r"(?is)<(style|head)[^>]*>.*?</\1>", "", markup)
    text = re.sub(r"(?i)<li[^>]*>", "- ", text)
    text = re.sub(r"(?i)</td>\s*<td[^>]*>", ": ", text)
    text = re.sub(r"(?i)<br\s*/?>|</(p|h1|tr|li|pre)>", "\n", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = html.unescape(text)
    lines = [line.strip() for line in text.splitlines() if line.strip()]

    # Keep "Label: value" rows and "- item" lists together; blank line between paragraphs.
    def is_row(line: str) -> bool:
        return line.startswith("- ") or bool(re.match(r"^[\w ]{1,30}: ", line))

    out: list[str] = []
    for i, line in enumerate(lines):
        if i and not (is_row(line) and is_row(lines[i - 1])):
            out.append("")
        out.append(line)
    return "\n".join(out)


def render(ntype: NotificationType, data: dict[str, Any], app_name: str) -> tuple[str, str, str]:
    context = {**data, "app_name": app_name}
    subject = _text_env.from_string(ntype.subject).render(context)
    subject = " ".join(subject.split())[:200]  # no newlines, sensible length
    body_html = _html_env.get_template(ntype.template).render(context)
    return subject, body_html, html_to_text(body_html)
