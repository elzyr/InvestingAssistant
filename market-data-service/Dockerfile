FROM python:3.12-slim AS builder

WORKDIR /build

RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY requirements.txt .
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install -r requirements.txt && \
    pip uninstall -y pip && \
    find /opt/venv -type d -name "__pycache__" -prune -exec rm -rf {} +

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:$PATH"

WORKDIR /app

RUN useradd -m -u 1000 appuser && \
    chown appuser:appuser /app && \
    rm -rf /usr/local/bin/pip* /usr/local/lib/python*/site-packages/pip* /usr/local/lib/python*/ensurepip

COPY --from=builder --chown=appuser:appuser /opt/venv /opt/venv
COPY --chown=appuser:appuser app /app/app

USER appuser

EXPOSE 8083

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8083"]
