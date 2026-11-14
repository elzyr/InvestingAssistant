FROM python:3.12-slim AS builder

WORKDIR /build

RUN apt-get update && \
    apt-get install -y --no-install-recommends binutils && \
    rm -rf /var/lib/apt/lists/* && \
    python -m venv /opt/venv

ENV PATH="/opt/venv/bin:$PATH"

COPY requirements.txt .
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --no-cache-dir -r requirements.txt && \
    pip uninstall -y pip && \
    find /opt/venv -type d \( -name "tests" -o -name "test" -o -name "__pycache__" -o -name "f2py" \) -prune -exec rm -rf {} + && \
    find /opt/venv -type f \( -name "*.pyc" -o -name "*.pyo" -o -name "*.pyi" -o -name "*.c" -o -name "*.cpp" -o -name "*.h" -o -name "*.hpp" -o -name "*.pyx" -o -name "*.pxd" -o -name "*.a" -o -name "conftest.py" \) -delete && \
    find /opt/venv -type f -name "*.so*" -exec strip --strip-unneeded {} +

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:$PATH"

WORKDIR /app

RUN useradd -m -u 1000 appuser && \
    chown appuser:appuser /app && \
    rm -rf /usr/local/bin/pip* \
           /usr/local/lib/python*/site-packages/pip* \
           /usr/local/lib/python*/ensurepip \
           /usr/local/lib/python*/test \
           /usr/local/lib/python*/idlelib \
           /usr/local/lib/python*/tkinter \
           /usr/local/lib/python*/turtledemo \
           /usr/local/lib/python*/pydoc_data \
           /usr/local/lib/python*/lib2to3 \
           /usr/share/doc/* \
           /usr/share/man/* \
           /usr/share/common-licenses/* \
           /usr/share/gcc/* && \
    find /usr/local/lib/python* -type d -name "__pycache__" -prune -exec rm -rf {} +

COPY --from=builder --chown=appuser:appuser /opt/venv /opt/venv
COPY --chown=appuser:appuser app /app/app

USER appuser

EXPOSE 8083

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${APP_PORT:-8083}"]
